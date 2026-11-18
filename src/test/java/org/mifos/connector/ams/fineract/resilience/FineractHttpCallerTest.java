package org.mifos.connector.ams.fineract.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.converter.stream.InputStreamCache;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.http.conn.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class FineractHttpCallerTest {

    private static final String PATH = "/fineract-provider/api/v1/paymentHub/confirmation";
    private static final String CONFIRMATION = "fineract-confirmation-localhost";

    private WireMockServer fineract;
    private CamelContext camelContext;
    private ProducerTemplate producerTemplate;
    private FineractResilienceProperties properties;
    private MeterRegistry meterRegistry;
    private FineractHttpCaller caller;

    @BeforeEach
    void setUp() {
        fineract = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        fineract.start();
        camelContext = new DefaultCamelContext();
        camelContext.start();
        producerTemplate = camelContext.createProducerTemplate();
        meterRegistry = new SimpleMeterRegistry();
        properties = new FineractResilienceProperties();
        for (FineractEndpoint endpoint : FineractEndpoint.values()) {
            FineractResilienceProperties.EndpointSettings settings = properties.settingsFor(endpoint);
            settings.setConnectTimeout(Duration.ofMillis(500));
            settings.setReadTimeout(Duration.ofMillis(300));
            settings.getRetry().setInitialBackoff(Duration.ofMillis(10));
            settings.getCircuitBreaker().setSlidingWindowSize(10);
            settings.getCircuitBreaker().setMinimumNumberOfCalls(10);
            settings.getCircuitBreaker().setWaitDurationInOpenState(Duration.ofMillis(300));
            settings.getCircuitBreaker().setPermittedCallsInHalfOpenState(1);
        }
        caller = newCaller();
    }

    @AfterEach
    void tearDown() throws Exception {
        producerTemplate.close();
        camelContext.close();
        fineract.stop();
    }

    @Test
    void successfulCallIsSentOnce() {
        stub(200);

        Exchange exchange = call();

        assertEquals(FineractCallOutcome.RESPONDED, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertTrue(FineractHttpCaller.responded(exchange));
        assertEquals(200, status(exchange));
        assertEquals("{\"status\":\"ok\"}", exchange.getMessage().getBody(String.class));
        fineract.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retryableStatusIsRetriedUntilItSucceeds() {
        fineract.stubFor(post(PATH).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        fineract.stubFor(post(PATH).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        Exchange exchange = call();

        assertEquals(200, status(exchange));
        fineract.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retriedRequestCarriesTheMarshalledBodyAgain() {
        fineract.stubFor(post(PATH).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        fineract.stubFor(post(PATH).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
        Exchange exchange = newExchange();
        // what .marshal().json() leaves on the message when stream caching is on
        exchange.getMessage()
                .setBody(new InputStreamCache("{\"data\":{\"id\":\"txn-1\"}}".getBytes(StandardCharsets.UTF_8)));

        caller.call(FineractEndpoint.CONFIRMATION, uri(), exchange);

        assertEquals(200, status(exchange));
        for (LoggedRequest request : fineract.findAll(postRequestedFor(urlEqualTo(PATH)))) {
            assertEquals("{\"data\":{\"id\":\"txn-1\"}}", request.getBodyAsString());
        }
        assertEquals(2, fineract.findAll(postRequestedFor(urlEqualTo(PATH))).size());
    }

    @Test
    void retriesStopAtMaxAttemptsAndKeepTheLastResponse() {
        stub(503);

        Exchange exchange = call();

        assertEquals(FineractCallOutcome.RESPONDED, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertEquals(503, status(exchange));
        assertEquals("status:503", exchange.getProperty(FineractHttpCaller.CALL_FAILURE));
        fineract.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void serverErrorOutsideTheRetryListIsNotRetried() {
        stub(500);

        Exchange exchange = call();

        assertEquals(500, status(exchange));
        assertEquals("status:500", exchange.getProperty(FineractHttpCaller.CALL_FAILURE));
        fineract.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void clientErrorIsNotRetried() {
        stub(400);

        Exchange exchange = call();

        assertEquals(400, status(exchange));
        assertNull(exchange.getProperty(FineractHttpCaller.CALL_FAILURE));
        fineract.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void connectionFailureIsRetriedAndReportedAsNotSent() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        Exchange exchange = newExchange();
        caller.call(FineractEndpoint.CONFIRMATION,
                "http://localhost:" + closedPort + PATH + "?throwExceptionOnFailure=false", exchange);

        assertEquals(FineractCallOutcome.NOT_SENT, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertFalse(FineractHttpCaller.responded(exchange));
        assertTrue(exchange.getProperty(FineractHttpCaller.CALL_FAILURE, String.class).startsWith("not_sent:"));
        assertNull(exchange.getException());
        assertNull(exchange.getMessage().getHeader("Authorization"), "the request must not be left on the message");
        assertNull(exchange.getMessage().getBody());
        assertEquals(1.0, meterRegistry.get("resilience4j.retry.calls").tag("name", CONFIRMATION)
                .tag("kind", "failed_with_retry").functionCounter().count());
    }

    @Test
    void confirmationTimeoutIsNotRetriedAndReportedAsUnknown() {
        fineract.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = call();

        assertEquals(FineractCallOutcome.UNKNOWN, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertTrue(exchange.getProperty(FineractHttpCaller.CALL_FAILURE, String.class).startsWith("no_response:"));
        assertNull(exchange.getMessage().getBody(), "the request must not be left on the message");
        fineract.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void timeoutIsRetriedWhenTheOutcomeUnknownFlagIsOn() {
        properties.getConfirmation().getRetry().setRetryWhenOutcomeUnknown(true);
        caller = newCaller();
        fineract.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = call();

        assertEquals(FineractCallOutcome.UNKNOWN, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        fineract.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void clientDetailsTimeoutIsRetriedByDefault() {
        fineract.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = call(FineractEndpoint.CLIENT_DETAILS);

        assertEquals(FineractCallOutcome.UNKNOWN, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        fineract.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void verificationServerErrorIsNotRetriedButGatewayErrorIs() {
        stub(500);
        call(FineractEndpoint.VERIFICATION);
        fineract.verify(1, postRequestedFor(urlEqualTo(PATH)));

        fineract.resetAll();
        stub(504);
        call(FineractEndpoint.VERIFICATION);

        fineract.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void eachEndpointHasItsOwnCircuit() {
        singleAttemptWithSmallWindow();
        stub(503);
        call();
        call();
        fineract.resetAll();
        stub(200);

        assertEquals("circuit_open", call().getProperty(FineractHttpCaller.CALL_FAILURE));
        assertEquals(200, status(call(FineractEndpoint.VERIFICATION)));
        assertEquals(1.0, circuitState("fineract-verification-localhost", "closed"));
    }

    @Test
    void circuitOpensFailsFastThenClosesAfterSuccessfulProbe() {
        singleAttemptWithSmallWindow();
        stub(503);
        call();
        call();

        Exchange rejected = call();

        assertEquals(FineractCallOutcome.NOT_SENT, rejected.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertEquals("circuit_open", rejected.getProperty(FineractHttpCaller.CALL_FAILURE));
        fineract.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertEquals(1.0, circuitState(CONFIRMATION, "open"));

        waitForOpenStateToEnd();
        fineract.resetAll();
        stub(200);
        Exchange probe = call();

        assertEquals(200, status(probe));
        assertEquals(1.0, circuitState(CONFIRMATION, "closed"));
    }

    @Test
    void failedProbeReopensTheCircuit() {
        singleAttemptWithSmallWindow();
        stub(503);
        call();
        call();
        waitForOpenStateToEnd();

        call();

        assertEquals(1.0, circuitState(CONFIRMATION, "open"));
        assertEquals("circuit_open", call().getProperty(FineractHttpCaller.CALL_FAILURE));
        fineract.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void eachHostHasItsOwnCircuit() {
        singleAttemptWithSmallWindow();
        stub(503);
        call();
        call();
        fineract.resetAll();
        stub(200);

        assertEquals("circuit_open", call().getProperty(FineractHttpCaller.CALL_FAILURE));
        Exchange otherHost = newExchange();
        caller.call(FineractEndpoint.CONFIRMATION,
                "http://127.0.0.1:" + fineract.port() + PATH + "?throwExceptionOnFailure=false", otherHost);
        assertEquals(200, status(otherHost));
    }

    @Test
    void hostIsTakenFromTheUri() {
        assertEquals("loans.qa.oneacrefund.org", FineractHttpCaller
                .hostOf("https://loans.qa.oneacrefund.org:443/fineract-provider/api/v1/paymentHub/confirmation?x=1"));
        assertEquals("fineract.example", FineractHttpCaller.hostOf("https://fineract.example"));
        assertEquals("unknown", FineractHttpCaller.hostOf("direct:fineract"));
    }

    @Test
    void callThatCouldNotFinishBeforeTheJobDeadlineIsNotMade() {
        stub(200);
        properties.setJobDeadlineMargin(Duration.ofMillis(100));
        caller = newCaller();
        Exchange exchange = newExchange();
        exchange.setProperty(FineractHttpCaller.JOB_DEADLINE, System.currentTimeMillis() + 500);

        caller.call(FineractEndpoint.CONFIRMATION, uri(), exchange);

        assertEquals(FineractCallOutcome.NOT_SENT, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertEquals("job_deadline", exchange.getProperty(FineractHttpCaller.CALL_FAILURE));
        assertNull(exchange.getMessage().getHeader("Authorization"), "the request must not be left on the message");
        fineract.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retryThatCouldOverrunTheJobDeadlineIsNotMade() {
        stub(503);
        properties.setJobDeadlineMargin(Duration.ofMillis(100));
        properties.getConfirmation().getRetry().setInitialBackoff(Duration.ofMillis(300));
        caller = newCaller();
        Exchange exchange = newExchange();
        // Room for one attempt (2 x 500ms connect + 300ms read) plus 50ms; the first retry waits
        // at least 150ms.
        exchange.setProperty(FineractHttpCaller.JOB_DEADLINE, System.currentTimeMillis() + 100 + 1300 + 50);

        caller.call(FineractEndpoint.CONFIRMATION, uri(), exchange);

        assertEquals(FineractCallOutcome.RESPONDED, exchange.getProperty(FineractHttpCaller.CALL_OUTCOME));
        assertEquals("status:503", exchange.getProperty(FineractHttpCaller.CALL_FAILURE));
        assertEquals(503, status(exchange));
        fineract.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void clientErrorsDoNotOpenTheCircuit() {
        singleAttemptWithSmallWindow();
        stub(400);
        for (int i = 0; i < 5; i++) {
            call();
        }

        assertEquals(1.0, circuitState(CONFIRMATION, "closed"));
        fineract.verify(5, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void endpointTimeoutsAreAppliedToTheUri() {
        stub(200);
        properties.getConfirmation().setReadTimeout(Duration.ofSeconds(7));
        caller = newCaller();

        call();

        assertNotNull(camelContext.hasEndpoint(uri() + "&httpClient.connectTimeout=500"
                + "&httpClient.connectionRequestTimeout=500&httpClient.socketTimeout=7000"));
    }

    @Test
    void uriWithoutQueryStillGetsTheTimeouts() {
        stub(200);

        Exchange exchange = newExchange();
        caller.call(FineractEndpoint.CONFIRMATION, "http://localhost:" + fineract.port() + PATH, exchange);

        assertEquals(200, status(exchange));
    }

    @Test
    void failuresBeforeTheRequestLeftAreNotSentAndRetried() {
        FineractResilienceProperties.EndpointSettings settings = properties.getConfirmation();
        List<Exception> neverSent = List.of(new ConnectException(), new ConnectTimeoutException(),
                new UnknownHostException(), new NoRouteToHostException(), new SSLHandshakeException("handshake"),
                new IOException(new ConnectException()));

        for (Exception exception : neverSent) {
            FineractHttpCaller.FailedAttempt attempt = FineractHttpCaller.classify(exception, settings);
            assertEquals(FineractCallOutcome.NOT_SENT, attempt.outcome, exception.toString());
            assertTrue(attempt.retryable, exception.toString());
        }
    }

    @Test
    void otherNetworkFailuresHaveAnUnknownOutcomeAndFollowTheFlag() {
        FineractResilienceProperties.EndpointSettings settings = properties.getConfirmation();

        FineractHttpCaller.FailedAttempt attempt = FineractHttpCaller.classify(new SocketTimeoutException(), settings);

        assertEquals(FineractCallOutcome.UNKNOWN, attempt.outcome);
        assertFalse(attempt.retryable);
        assertEquals("no_response:SocketTimeoutException", attempt.getMessage());
        settings.getRetry().setRetryWhenOutcomeUnknown(true);
        assertTrue(FineractHttpCaller.classify(new SocketTimeoutException(), settings).retryable);
    }

    @Test
    void localErrorsAreNeitherRetriedNorCountedAgainstTheCircuit() {
        FineractResilienceProperties.EndpointSettings settings = properties.getConfirmation();
        FineractHttpCaller.FailedAttempt attempt = FineractHttpCaller.classify(new IllegalStateException(), settings);
        CircuitBreakerConfig circuitBreaker = FineractHttpCaller.circuitBreakerConfig(settings);
        final RetryConfig retry = FineractHttpCaller.retryConfig(settings);

        assertEquals(FineractCallOutcome.NOT_SENT, attempt.outcome);
        assertFalse(attempt.retryable);
        assertFalse(circuitBreaker.getRecordExceptionPredicate().test(attempt));
        assertTrue(circuitBreaker.getIgnoreExceptionPredicate().test(attempt));
        assertFalse(retry.getExceptionPredicate().test(attempt));
        assertFalse(circuitBreaker.getRecordExceptionPredicate().test(new IllegalStateException()));
        assertFalse(retry.getExceptionPredicate().test(new IllegalStateException()));
    }

    @Test
    void reasonsNameOnlyTheFailureType() {
        assertEquals("none", FineractHttpCaller.reason(null));
        assertEquals("IOException", FineractHttpCaller.reason(new IOException("http://host/path?secret")));
        assertEquals("status:503", FineractHttpCaller
                .reason(new FineractHttpCaller.FailedAttempt(FineractCallOutcome.RESPONDED, "status:503", true, true)));
    }

    @Test
    void emptyRetryStatusListIsAllowed() {
        FineractResilienceProperties.Retry retry = new FineractResilienceProperties.Retry();

        retry.setRetryStatusCodes(null);

        assertTrue(retry.getRetryStatusCodes().isEmpty());
    }

    private static void waitForOpenStateToEnd() {
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> true);
    }

    private void singleAttemptWithSmallWindow() {
        for (FineractEndpoint endpoint : FineractEndpoint.values()) {
            FineractResilienceProperties.EndpointSettings settings = properties.settingsFor(endpoint);
            settings.getRetry().setMaxAttempts(1);
            settings.getCircuitBreaker().setSlidingWindowSize(4);
            settings.getCircuitBreaker().setMinimumNumberOfCalls(2);
        }
        caller = newCaller();
    }

    private FineractHttpCaller newCaller() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", meterRegistry);
        return new FineractHttpCaller(producerTemplate, properties, beans.getBeanProvider(MeterRegistry.class));
    }

    private void stub(int status) {
        fineract.stubFor(post(PATH).willReturn(aResponse().withStatus(status).withBody("{\"status\":\"ok\"}")));
    }

    private String uri() {
        return "http://localhost:" + fineract.port() + PATH + "?throwExceptionOnFailure=false";
    }

    private Exchange call() {
        return call(FineractEndpoint.CONFIRMATION);
    }

    private Exchange call(FineractEndpoint endpoint) {
        Exchange exchange = newExchange();
        caller.call(endpoint, uri(), exchange);
        return exchange;
    }

    private Exchange newExchange() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader(Exchange.HTTP_METHOD, "POST");
        exchange.getMessage().setHeader("Authorization", "Bearer secret-token");
        exchange.getMessage().setBody("{\"data\":{\"amount\":\"100\"}}");
        return exchange;
    }

    private static Integer status(Exchange exchange) {
        return exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
    }

    private double circuitState(String name, String state) {
        return meterRegistry.get("resilience4j.circuitbreaker.state").tag("name", name).tag("state", state).gauge()
                .value();
    }
}
