package org.mifos.connector.ams.fineract.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.ams.fineract.camel.config.CamelProperties.CHANNEL_REQUEST;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_CODE;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.EXTERNAL_ID;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSFER_SETTLEMENT_FAILED;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.ams.fineract.FineractConnectorApplicationSetUp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the validation and settlement routes against a simulated Fineract that is slow or unavailable. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FineractResilienceFlowTest extends FineractConnectorApplicationSetUp {

    private static final String CONFIRMATION_PATH = "/paymentHub/confirmation";
    private static final String VERIFICATION_PATH = "/paymentHub/verification";
    private static final String PAYMENTS_PATH = "/paymentHub/payments";
    private static final WireMockServer FINERACT = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        FINERACT.start();
    }

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private FineractResilienceProperties properties;

    @DynamicPropertySource
    static void fineractSettings(DynamicPropertyRegistry registry) {
        registry.add("fineract.base-url", FINERACT::baseUrl);
        for (String endpoint : List.of("verification", "confirmation", "payments")) {
            registry.add("fineract.resilience." + endpoint + ".read-timeout", () -> "300ms");
            registry.add("fineract.resilience." + endpoint + ".retry.initial-backoff", () -> "10ms");
        }
        registry.add("fineract.resilience.confirmation.circuit-breaker.sliding-window-size", () -> "4");
        registry.add("fineract.resilience.confirmation.circuit-breaker.minimum-number-of-calls", () -> "4");
        registry.add("fineract.resilience.confirmation.circuit-breaker.wait-duration-in-open-state", () -> "500ms");
        registry.add("fineract.resilience.confirmation.circuit-breaker.permitted-calls-in-half-open-state", () -> "1");
    }

    @AfterAll
    static void stopFineract() {
        FINERACT.stop();
    }

    @BeforeEach
    void resetFineract() {
        FINERACT.resetAll();
    }

    @Test
    void settingsAreReadFromConfiguration() {
        assertEquals(Duration.ofMillis(300), properties.getConfirmation().getReadTimeout());
        assertEquals(Duration.ofSeconds(60), properties.getClientDetails().getReadTimeout());
        assertEquals(4, properties.getConfirmation().getCircuitBreaker().getMinimumNumberOfCalls());
        assertEquals(List.of(503), properties.getConfirmation().getRetry().getRetryStatusCodes());
        assertFalse(properties.getConfirmation().getRetry().isRetryWhenOutcomeUnknown());
        assertEquals(List.of(502, 503, 504), properties.getVerification().getRetry().getRetryStatusCodes());
        assertTrue(properties.getVerification().getRetry().isRetryWhenOutcomeUnknown());
        assertEquals(List.of(500, 502, 503, 504), properties.getClientDetails().getRetry().getRetryStatusCodes());
        assertEquals(Duration.ofSeconds(10), properties.getJobDeadlineMargin());
    }

    @Test
    void confirmationAnsweredWith200Settles() {
        FINERACT.stubFor(post(CONFIRMATION_PATH).willReturn(aResponse().withStatus(200).withBody("{}")));

        Exchange exchange = settle(Long.MAX_VALUE);

        assertFalse(exchange.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertNull(exchange.getProperty(ERROR_CODE));
        FINERACT.verify(1, postRequestedFor(urlEqualTo(CONFIRMATION_PATH)));
    }

    @Test
    void unavailableFineractIsRetriedThenSettlementFails() {
        FINERACT.stubFor(post(CONFIRMATION_PATH).willReturn(aResponse().withStatus(503).withBody("down")));

        Exchange exchange = settle(Long.MAX_VALUE);

        assertTrue(exchange.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertNull(exchange.getProperty(ERROR_CODE), "the HTTP status stays the error code");
        assertEquals(503, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        FINERACT.verify(3, postRequestedFor(urlEqualTo(CONFIRMATION_PATH)));
    }

    @Test
    void slowFineractGetsOneConfirmationAndSettlementFailsAsUnknown() {
        FINERACT.stubFor(post(CONFIRMATION_PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = settle(Long.MAX_VALUE);

        assertTrue(exchange.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertEquals("UNKNOWN_OUTCOME", exchange.getProperty(ERROR_CODE));
        assertTrue(exchange.getProperty(ERROR_INFORMATION, String.class).contains("no_response:"));
        assertNull(exchange.getMessage().getBody(), "the request is not left as a response");
        FINERACT.verify(1, postRequestedFor(urlEqualTo(CONFIRMATION_PATH)));
    }

    @Test
    void slowValidationIsRetriedThenFailsAsUnknown() {
        FINERACT.stubFor(post(VERIFICATION_PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = validate(Long.MAX_VALUE);

        assertTrue(exchange.getProperty(PARTY_LOOKUP_FAILED, Boolean.class));
        assertEquals("UNKNOWN_OUTCOME", exchange.getProperty(ERROR_CODE));
        FINERACT.verify(3, postRequestedFor(urlEqualTo(VERIFICATION_PATH)));
    }

    @Test
    void unavailableValidationIsRetriedThenFailsWithTheStatus() {
        FINERACT.stubFor(post(VERIFICATION_PATH).willReturn(aResponse().withStatus(503)));

        Exchange exchange = validate(Long.MAX_VALUE);

        assertTrue(exchange.getProperty(PARTY_LOOKUP_FAILED, Boolean.class));
        assertNull(exchange.getProperty(ERROR_CODE));
        assertEquals(503, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        FINERACT.verify(3, postRequestedFor(urlEqualTo(VERIFICATION_PATH)));
    }

    @Test
    void noTimeBeforeTheJobDeadlineFailsSettlementWithoutSending() {
        FINERACT.stubFor(post(CONFIRMATION_PATH).willReturn(aResponse().withStatus(200)));

        Exchange exchange = settle(System.currentTimeMillis());

        assertTrue(exchange.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertEquals("JOB_DEADLINE", exchange.getProperty(ERROR_CODE));
        FINERACT.verify(0, postRequestedFor(urlEqualTo(CONFIRMATION_PATH)));
    }

    @Test
    void slowPaymentsCallFailsAsUnknownAndLeavesNoResponseToParse() {
        FINERACT.stubFor(post(PAYMENTS_PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(CHANNEL_REQUEST, virtualAccountChannelRequest());
        exchange.setProperty(TRANSACTION_ID, "txn-1");
        exchange.setProperty(EXTERNAL_ID, "RKTQDM7W6S");
        exchange.setProperty(FineractHttpCaller.JOB_DEADLINE, Long.MAX_VALUE);

        producerTemplate.send("direct:transfer-validation-and-settlement-base", exchange);

        assertTrue(exchange.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertEquals("UNKNOWN_OUTCOME", exchange.getProperty(ERROR_CODE));
        assertNull(exchange.getProperty("amsPaymentResponse"));
        FINERACT.verify(1, postRequestedFor(urlEqualTo(PAYMENTS_PATH)));
    }

    @Test
    void openCircuitFailsFastAndClosesAgainAfterRecovery() {
        FINERACT.stubFor(post(CONFIRMATION_PATH).willReturn(aResponse().withStatus(503)));
        settle(Long.MAX_VALUE);
        settle(Long.MAX_VALUE);
        int sentWhileDown = FINERACT.findAll(postRequestedFor(urlEqualTo(CONFIRMATION_PATH))).size();

        Exchange rejected = settle(Long.MAX_VALUE);

        assertTrue(rejected.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertEquals("CIRCUIT_OPEN", rejected.getProperty(ERROR_CODE));
        assertEquals(sentWhileDown, FINERACT.findAll(postRequestedFor(urlEqualTo(CONFIRMATION_PATH))).size(),
                "nothing is sent while the circuit is open");

        FINERACT.resetAll();
        FINERACT.stubFor(post(CONFIRMATION_PATH).willReturn(aResponse().withStatus(200)));
        await().pollDelay(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until(() -> true);
        Exchange probe = settle(Long.MAX_VALUE);

        assertFalse(probe.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        assertFalse(settle(Long.MAX_VALUE).getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
        FINERACT.verify(2, postRequestedFor(urlEqualTo(CONFIRMATION_PATH)));
    }

    private Exchange settle(long jobDeadline) {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(CHANNEL_REQUEST, channelRequest());
        exchange.setProperty(TRANSACTION_ID, "txn-1");
        exchange.setProperty(EXTERNAL_ID, "RKTQDM7W6S");
        exchange.setProperty(TRANSACTION_FAILED, false);
        exchange.setProperty(FineractHttpCaller.JOB_DEADLINE, jobDeadline);
        producerTemplate.send("direct:transfer-settlement-base", exchange);
        return exchange;
    }

    private Exchange validate(long jobDeadline) {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(CHANNEL_REQUEST, channelRequest());
        exchange.setProperty(TRANSACTION_ID, "txn-1");
        exchange.setProperty(FineractHttpCaller.JOB_DEADLINE, jobDeadline);
        producerTemplate.send("direct:transfer-validation-base", exchange);
        return exchange;
    }

    private static JSONObject channelRequest() {
        return new JSONObject("{\"payer\":{\"partyIdInfo\":{\"partyIdType\":\"MSISDN\","
                + "\"partyIdentifier\":\"250788000000\"}},\"payee\":{\"partyIdInfo\":"
                + "{\"partyIdType\":\"FOUNDATIONALID\",\"partyIdentifier\":\"25598208\"}},"
                + "\"amount\":{\"amount\":\"42.00\",\"currency\":\"RWF\"}}");
    }

    private static String virtualAccountChannelRequest() {
        return "{\"payer\":{\"partyIdInfo\":{\"partyIdType\":\"VIRTUAL_ACCOUNT_NUMBER\","
                + "\"partyIdentifier\":\"33321\"}},\"payee\":{\"partyIdInfo\":{\"partyIdType\":\"FINERACTACCOUNTID\","
                + "\"partyIdentifier\":\"1234\"}},\"amount\":{\"amount\":1000,\"currency\":\"NGN\"},"
                + "\"transactionType\":{\"scenario\":\"SQUAD\"}}";
    }
}
