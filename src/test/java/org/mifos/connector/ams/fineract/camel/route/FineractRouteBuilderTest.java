package org.mifos.connector.ams.fineract.camel.route;

import static org.junit.jupiter.api.Assertions.*;
import static org.mifos.connector.ams.fineract.camel.config.CamelProperties.*;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.*;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.language.ConstantExpression;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mifos.connector.ams.fineract.FineractConnectorApplicationTest;
import org.mifos.connector.ams.fineract.data.FineractGetValidationResponse;
import org.mifos.connector.ams.fineract.util.ConnectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FineractRouteBuilderTest extends FineractConnectorApplicationTest {

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private FluentProducerTemplate fluentProducerTemplate;

    @EndpointInject("mock:mockTransfer-settlement-base")
    protected MockEndpoint mockTransferSettlementEndpoint;

    @EndpointInject("mock:mockTransfer-validation-base")
    protected MockEndpoint mockTransferValidationEndpoint;

    @EndpointInject("mock:mockTransfer-get-client-details")
    protected MockEndpoint mockGetClientDetailsEndpoint;

    @EndpointInject("mock:mockAmsConfirmationEndpoint")
    protected MockEndpoint mockAmsConfirmationEndpoint;

    @EndpointInject("mock:mockAmsValidationEndpoint")
    protected MockEndpoint mockAmsValidationEndpoint;

    @EndpointInject("mock:mockGetClientDetailsEndpoint")
    protected MockEndpoint mockGetClientDetailsExternalEndpoint;

    @Value("${fineract.endpoint.client-details}")
    private String clientDetailsEndpoint;

    @Value("${fineract.base-url}")
    private String fineractBaseUrl;

    @Value("${fineract.endpoint.validation}")
    private String validationEndpoint;
    @Value("${fineract.endpoint.confirmation}")
    private String confirmationEndpoint;

    @Value("${ams.timeout}")
    private Integer amsTimeout;

    @BeforeEach
    void setUp() {
        mockTransferSettlementEndpoint.reset();
        mockAmsConfirmationEndpoint.reset();
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called")
    @Test
    void testTransferSettlementBaseCallsTransferSettlement() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-settlement").replace().to(mockTransferSettlementEndpoint);
        });

        mockTransferSettlementEndpoint.expectedMessageCount(1);

        camelContext.start();

        fluentProducerTemplate.to("direct:transfer-settlement-base").request(Exchange.class);

        mockTransferSettlementEndpoint.assertIsSatisfied();

    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and when the status is successful with response code 200, the TRANSFER_SETTLEMENT_FAILED property should be false.")
    @Test
    void testTransferSettlementBaseRouteSuccess() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-settlement").replace().to(mockTransferSettlementEndpoint);
        });

        mockTransferSettlementEndpoint.expectedMessageCount(1);
        mockTransferSettlementEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_FAILED, false);
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement-base").request(Exchange.class);

        mockTransferSettlementEndpoint.assertIsSatisfied();
        assertFalse(result.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and when the status is for pay bill is successful with response 200, the TRANSFER_SETTLEMENT_FAILED property should be false.")
    @Test
    void testTransferSettlementBaseRouteSuccessForPaybillFlow() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-settlement").replace().to(mockTransferSettlementEndpoint);
        });

        mockTransferSettlementEndpoint.expectedMessageCount(1);
        mockTransferSettlementEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(CONFIRMATION_RECEIVED, true);
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement-base").request(Exchange.class);

        mockTransferSettlementEndpoint.assertIsSatisfied();
        assertFalse(result.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and when the response code from ams is not 200, the TRANSFER_SETTLEMENT_FAILED property should be true.")
    @Test
    void testTransferSettlementBaseRouteFailureWhenTheResponseStatusFromAMSis400() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-settlement").replace().to(mockTransferSettlementEndpoint);
        });

        mockTransferSettlementEndpoint.expectedMessageCount(1);
        mockTransferSettlementEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "400");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement-base").request(Exchange.class);

        mockTransferSettlementEndpoint.assertIsSatisfied();
        assertTrue(result.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and when the response code from ams is 200 but the body is not true, the TRANSFER_SETTLEMENT_FAILED property should be true.")
    @Test
    void testTransferSettlementBaseRouteFailureWhenTheResponseIs200WithAFailureBody() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-settlement").replace().to(mockTransferSettlementEndpoint);
        });

        mockTransferSettlementEndpoint.expectedMessageCount(1);
        mockTransferSettlementEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
            exchange.setProperty(TRANSACTION_FAILED, true);

        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement-base").request(Exchange.class);

        mockTransferSettlementEndpoint.assertIsSatisfied();
        assertTrue(result.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and when the response code from ams is 200 but the body is not true for pay bill, the TRANSFER_SETTLEMENT_FAILED property should be true.")
    @Test
    void testTransferSettlementBaseRouteFailureWhenTheResponseIs200WithAFailureBodyForPayBill() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-settlement").replace().to(mockTransferSettlementEndpoint);
        });

        mockTransferSettlementEndpoint.expectedMessageCount(1);
        mockTransferSettlementEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
            exchange.setProperty(CONFIRMATION_RECEIVED, false);
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement-base").request(Exchange.class);

        mockTransferSettlementEndpoint.assertIsSatisfied();
        assertTrue(result.getProperty(TRANSFER_SETTLEMENT_FAILED, Boolean.class));
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and the body should be as expected for pay bill.")
    @Test
    void testTransferSettlementRouteReturnsExpectedResponse() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement", routeBuilder -> {
            routeBuilder
                    .weaveByToUri(fineractBaseUrl + confirmationEndpoint
                            + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                            + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                    .replace().to(mockAmsConfirmationEndpoint);
        });
        mockAmsConfirmationEndpoint.expectedMessageCount(1);
        mockAmsConfirmationEndpoint.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockAmsConfirmationEndpoint.expectedHeaderReceived("Content-Type", "application/json");

        String mockedConfirmationRequest = """
                    {
                        "secondaryIdentifier": {
                            "key": "foundationalId",
                            "value": "12345678"
                        },
                        "primaryIdentifier": {
                            "key": "MSISDN",
                            "value": "324567"
                        },
                        "customData": [
                         {
                                    "key": "transactionId",
                                    "value": "670d65bd-4efd-4a6c-ae2c-7fdaa8cb4d60"
                                },
                                {
                                    "key": "currency",
                                    "value": "RWF"
                                },
                                {
                                    "key": "amount",
                                    "value": "1000"
                                }
                        ]
                    }
                """;
        // Trigger the route
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement").withBody(mockedConfirmationRequest)
                .request(Exchange.class);

        // Verify the expectations
        mockAmsConfirmationEndpoint.assertIsSatisfied();

        Exchange exchange = mockAmsConfirmationEndpoint.getExchanges().get(0);
        String receivedBody = exchange.getIn().getBody(String.class);

        assertNotNull(result);
        assertTrue(receivedBody.contains("\"Currency\":\"RWF\""));
        assertTrue(receivedBody.contains("\"Amount\":1000"));
        assertTrue(receivedBody.contains("\"Account\":\"324567\""));
        assertTrue(receivedBody.contains("\"RemoteTransactionId\":\"670d65bd-4efd-4a6c-ae2c-7fdaa8cb4d60\""));
    }

    @DisplayName("When the transfer settlement base route is called, the transfer settlement route should be called, and the body should be as expected for buy goods.")
    @Test
    void testTransferSettlementRouteWithChanelRequest() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement", routeBuilder -> {
            routeBuilder
                    .weaveByToUri(fineractBaseUrl + confirmationEndpoint
                            + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                            + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                    .replace().to(mockAmsConfirmationEndpoint);
        });

        mockAmsConfirmationEndpoint.expectedMessageCount(1);
        mockAmsConfirmationEndpoint.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockAmsConfirmationEndpoint.expectedHeaderReceived("Content-Type", "application/json");

        JSONObject channelRequestObject = getSettlementJsonObject();

        Exchange exchange = camelContext.getEndpoint("direct:transfer-settlement").createExchange();
        exchange.setProperty(CHANNEL_REQUEST, channelRequestObject);
        exchange.setProperty(EXTERNAL_ID, "ef01bdd4-439e-4a70-b8da-cc81f2dd34d0");
        exchange.setProperty(TRANSACTION_ID, "af71cac2-ec74-4bd1-a584-ea7b72d73982");

        // Trigger the route
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement").withExchange(exchange).send();

        // Verify the expectations
        mockAmsConfirmationEndpoint.assertIsSatisfied();

        Exchange exchangeResult = mockAmsConfirmationEndpoint.getExchanges().get(0);
        String receivedBody = exchangeResult.getIn().getBody(String.class);
        assertNotNull(result);
        assertTrue(receivedBody.contains("\"Currency\":\"RWF\""));
        assertTrue(receivedBody.contains("\"Amount\":42.00"));
        assertTrue(receivedBody.contains("\"Account\":\"25598208\""));
        assertTrue(receivedBody.contains("\"RemoteTransactionId\":\"af71cac2-ec74-4bd1-a584-ea7b72d73982\""));
        assertTrue(receivedBody.contains("\"Status\":\"successful\""));
        assertTrue(receivedBody.contains("\"ReceiptId\":\"ef01bdd4-439e-4a70-b8da-cc81f2dd34d0\""));
        assertTrue(receivedBody.contains("\"PhoneNumber\":\"250788000000\""));
        assertTrue(receivedBody.contains("\"getAccountDetails\":false"));
    }

    @DisplayName("When the transfer settlement base route is called with useWorkflowIdAsTransactionIdObj, the transfer settlement route should be called, and the body should be as expected with useWorkflowIdAsTransactionIdObj as transaction id.")
    @Test
    void testTransferSettlementRouteWithUseWorkFlowIdAsTransactionId() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement", routeBuilder -> {
            routeBuilder
                    .weaveByToUri(fineractBaseUrl + confirmationEndpoint
                            + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                            + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                    .replace().to(mockAmsConfirmationEndpoint);
        });

        mockAmsConfirmationEndpoint.expectedMessageCount(1);
        mockAmsConfirmationEndpoint.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockAmsConfirmationEndpoint.expectedHeaderReceived("Content-Type", "application/json");

        JSONObject channelRequestObject = getUseWorkflowIdAsTransactionIdChannelRequest();

        Exchange exchange = camelContext.getEndpoint("direct:transfer-settlement").createExchange();
        exchange.setProperty(CHANNEL_REQUEST, channelRequestObject);
        exchange.setProperty(TRANSACTION_ID, "af71cac2-ec74-4bd1-a584-ea7b72d73982");

        // Trigger the route
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement").withExchange(exchange).send();

        // Verify the expectations
        mockAmsConfirmationEndpoint.assertIsSatisfied();

        Exchange exchangeResult = mockAmsConfirmationEndpoint.getExchanges().get(0);
        String receivedBody = exchangeResult.getIn().getBody(String.class);
        assertNotNull(result);
        assertTrue(receivedBody.contains("\"Currency\":\"RWF\""));
        assertTrue(receivedBody.contains("\"Amount\":42.00"));
        assertTrue(receivedBody.contains("\"Account\":\"25598208\""));
        assertTrue(receivedBody.contains("\"RemoteTransactionId\":\"b3156ea5-3240-4661-8bb7-7c6abd35380d\""));
        assertTrue(receivedBody.contains("\"Status\":\"successful\""));
        assertTrue(receivedBody.contains("\"ReceiptId\":\"ef01bdd4-439e-4a70-b8da-cc81f2dd34d0\""));
        assertTrue(receivedBody.contains("\"PhoneNumber\":\"250788000000\""));
        assertTrue(receivedBody.contains("\"getAccountDetails\":false"));
    }

    @DisplayName("When the transfer settlement base route is called with useWorkflowIdAsTransactionIdObj as false, the transfer settlement route should be called, and the body should be as expected with useWorkflowIdAsTransactionIdObj not as transaction id.")
    @Test
    void testTransferSettlementRouteUseWorkFlowIdAsTransactionIdAsFalse() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-settlement", routeBuilder -> {
            routeBuilder
                    .weaveByToUri(fineractBaseUrl + confirmationEndpoint
                            + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                            + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                    .replace().to(mockAmsConfirmationEndpoint);
        });

        mockAmsConfirmationEndpoint.expectedMessageCount(1);
        mockAmsConfirmationEndpoint.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockAmsConfirmationEndpoint.expectedHeaderReceived("Content-Type", "application/json");

        Exchange exchange = camelContext.getEndpoint("direct:transfer-settlement").createExchange();
        exchange.setProperty(CHANNEL_REQUEST, getUseWorkflowIdAsTransactionIdChannelRequestWithUseWorkFlowAsFalse());
        exchange.setProperty(TRANSACTION_ID, "af71cac2-ec74-4bd1-a584-ea7b72d73982");

        // Trigger the route
        Exchange result = fluentProducerTemplate.to("direct:transfer-settlement").withExchange(exchange).send();

        // Verify the expectations
        mockAmsConfirmationEndpoint.assertIsSatisfied();

        Exchange exchangeResult = mockAmsConfirmationEndpoint.getExchanges().get(0);
        String receivedBody = exchangeResult.getIn().getBody(String.class);
        assertNotNull(result);
        assertTrue(receivedBody.contains("\"Currency\":\"RWF\""));
        assertTrue(receivedBody.contains("\"Amount\":42.00"));
        assertTrue(receivedBody.contains("\"Account\":\"25598208\""));
        assertTrue(receivedBody.contains("\"RemoteTransactionId\":\"af71cac2-ec74-4bd1-a584-ea7b72d73982\""));
        assertTrue(receivedBody.contains("\"Status\":\"successful\""));
        assertTrue(receivedBody.contains("\"ReceiptId\":\"ef01bdd4-439e-4a70-b8da-cc81f2dd34d0\""));
        assertTrue(receivedBody.contains("\"PhoneNumber\":\"250788000000\""));
        assertTrue(receivedBody.contains("\"getAccountDetails\":false"));
    }

    @DisplayName("When the transfer validation base route is called, the transfer validation route should be called, and when the status is successful with response code 200, the PARTY_LOOKUP_FAILED property should be false.")
    @Test
    void testTransferValidationBaseRouteSuccess() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-validation-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-validation").replace().to(mockTransferValidationEndpoint);
        });

        mockTransferValidationEndpoint.expectedMessageCount(1);
        mockTransferValidationEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_ID, "d475def4-0130-4b84-9671-aa9d624c9a38");
            exchange.setProperty(AMOUNT_VARIABLE_NAME, 1000);
            exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "mockName");
            exchange.setProperty(CURRENCY_VARIABLE_NAME, "rwf");
            exchange.setProperty(MSISDN_VARIABLE_NAME, "mockMsisdn");
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-validation-base").request(Exchange.class);

        mockTransferValidationEndpoint.assertIsSatisfied();
        assertEquals(false, result.getProperty(PARTY_LOOKUP_FAILED));
        assertEquals(1000, result.getProperty(AMOUNT_VARIABLE_NAME));
        assertEquals("mockName", result.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
        assertEquals("rwf", result.getProperty(CURRENCY_VARIABLE_NAME));
        assertEquals("mockMsisdn", result.getProperty(MSISDN_VARIABLE_NAME));
        assertEquals("d475def4-0130-4b84-9671-aa9d624c9a38", result.getProperty(TRANSACTION_ID));

    }

    @DisplayName("When the transfer validation base route is called, the transfer validation route should be called, and when the status is successful with response code is not 200, the PARTY_LOOKUP_FAILED property should be true.")
    @Test
    void testTransferValidationBaseRoutesFailure() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-validation-base", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-validation").replace().to(mockTransferValidationEndpoint);
        });

        mockTransferValidationEndpoint.expectedMessageCount(1);
        mockTransferValidationEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_ID, "d475def4-0130-4b84-9671-aa9d624c9a38");
            exchange.setProperty(AMOUNT_VARIABLE_NAME, 1000);
            exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "mockName");
            exchange.setProperty(CURRENCY_VARIABLE_NAME, "rwf");
            exchange.setProperty(MSISDN_VARIABLE_NAME, "mockMsisdn");
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "400");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-validation-base").request(Exchange.class);

        mockTransferValidationEndpoint.assertIsSatisfied();
        assertEquals(true, result.getProperty(PARTY_LOOKUP_FAILED));
        assertEquals(1000, result.getProperty(AMOUNT_VARIABLE_NAME));
        assertEquals("mockName", result.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
        assertEquals("rwf", result.getProperty(CURRENCY_VARIABLE_NAME));
        assertEquals("mockMsisdn", result.getProperty(MSISDN_VARIABLE_NAME));
        assertEquals("d475def4-0130-4b84-9671-aa9d624c9a38", result.getProperty(TRANSACTION_ID));

    }

    @DisplayName("When the transfer validation base route is called with client info check, the transfer validation route should be called, and when the status is successful with response code 200, the PARTY_LOOKUP_FAILED property should be false, and add client info.")
    @Test
    void testTransferValidationBaseRouteSuccessWithClientDetailsCheck() throws Exception {
        FineractGetValidationResponse mockResponse = new FineractGetValidationResponse();
        mockResponse.setClientFirstname("mockName");
        mockResponse.setClientLastname("lastNam");
        AdviceWith.adviceWith(camelContext, "transfer-validation-base", routeBuilder -> {
            ObjectMapper mapper = new ObjectMapper();

            routeBuilder.weaveByToUri("direct:transfer-validation").replace().to(mockTransferValidationEndpoint);
            routeBuilder.weaveByToUri("direct:get-client-details").replace()
                    .setBody(new ConstantExpression(mapper.writeValueAsString(mockResponse)));
        });

        mockTransferValidationEndpoint.expectedMessageCount(1);
        mockTransferValidationEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_ID, "d475def4-0130-4b84-9671-aa9d624c9a38");
            exchange.setProperty(AMOUNT_VARIABLE_NAME, 1000);
            exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "mockName");
            exchange.setProperty(CURRENCY_VARIABLE_NAME, "rwf");
            exchange.setProperty(MSISDN_VARIABLE_NAME, "mockMsisdn");
            exchange.setProperty(GET_ACCOUNT_DETAILS_FLAG, true);
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        mockGetClientDetailsEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_ID, "d475def4-0130-4b84-9671-aa9d624c9a38");
            exchange.setProperty(AMOUNT_VARIABLE_NAME, 1000);
            exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "mockName");
            exchange.setProperty(CURRENCY_VARIABLE_NAME, "rwf");
            exchange.setProperty(MSISDN_VARIABLE_NAME, "mockMsisdn");
            exchange.setProperty(GET_ACCOUNT_DETAILS_FLAG, true);
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-validation-base").request(Exchange.class);

        mockTransferValidationEndpoint.assertIsSatisfied();
        assertEquals(false, result.getProperty(PARTY_LOOKUP_FAILED));
        assertEquals(1000, result.getProperty(AMOUNT_VARIABLE_NAME));
        assertEquals("mockName", result.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
        assertEquals("rwf", result.getProperty(CURRENCY_VARIABLE_NAME));
        assertEquals("mockMsisdn", result.getProperty(MSISDN_VARIABLE_NAME));
        assertEquals("d475def4-0130-4b84-9671-aa9d624c9a38", result.getProperty(TRANSACTION_ID));
        assertEquals(mockResponse.getClientFirstname() + " " + mockResponse.getClientLastname(),
                result.getProperty(CLIENT_NAME_VARIABLE_NAME));

    }

    @DisplayName("When the transfer validation base route is called with client info check, the transfer validation route should be called, and when the status is successful with response code 200, the PARTY_LOOKUP_FAILED property should be false.")
    @Test
    void testTransferValidationBaseRouteSuccessWithClientDetailsCheckWithNullResponse() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-validation-base", routeBuilder -> {
            ObjectMapper mapper = new ObjectMapper();

            routeBuilder.weaveByToUri("direct:transfer-validation").replace().to(mockTransferValidationEndpoint);
            routeBuilder.weaveByToUri("direct:get-client-details").replace().setBody(new ConstantExpression(null));
        });

        mockTransferValidationEndpoint.expectedMessageCount(1);
        mockTransferValidationEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_ID, "d475def4-0130-4b84-9671-aa9d624c9a38");
            exchange.setProperty(AMOUNT_VARIABLE_NAME, 1000);
            exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "mockName");
            exchange.setProperty(CURRENCY_VARIABLE_NAME, "rwf");
            exchange.setProperty(MSISDN_VARIABLE_NAME, "mockMsisdn");
            exchange.setProperty(GET_ACCOUNT_DETAILS_FLAG, true);
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        mockGetClientDetailsEndpoint.whenAnyExchangeReceived(exchange -> {
            exchange.setProperty(TRANSACTION_ID, "d475def4-0130-4b84-9671-aa9d624c9a38");
            exchange.setProperty(AMOUNT_VARIABLE_NAME, 1000);
            exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "mockName");
            exchange.setProperty(CURRENCY_VARIABLE_NAME, "rwf");
            exchange.setProperty(MSISDN_VARIABLE_NAME, "mockMsisdn");
            exchange.setProperty(GET_ACCOUNT_DETAILS_FLAG, true);
            exchange.getMessage().setHeader(CAMEL_HTTP_RESPONSE_CODE, "200");
        });
        camelContext.start();
        Exchange result = fluentProducerTemplate.to("direct:transfer-validation-base").request(Exchange.class);

        mockTransferValidationEndpoint.assertIsSatisfied();
        assertEquals(false, result.getProperty(PARTY_LOOKUP_FAILED));
        assertEquals(1000, result.getProperty(AMOUNT_VARIABLE_NAME));
        assertEquals("mockName", result.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
        assertEquals("rwf", result.getProperty(CURRENCY_VARIABLE_NAME));
        assertEquals("mockMsisdn", result.getProperty(MSISDN_VARIABLE_NAME));
        assertEquals("d475def4-0130-4b84-9671-aa9d624c9a38", result.getProperty(TRANSACTION_ID));

    }

    @DisplayName("When the transfer validation route is called with the pay bill flow it should return the expected response.")
    @Test
    void testTransferValidationRoutePayBillFlow() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-validation", routeBuilder -> {
            routeBuilder
                    .weaveByToUri(
                            fineractBaseUrl + validationEndpoint + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                                    + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                    .replace().to(mockAmsValidationEndpoint);
        });

        mockAmsValidationEndpoint.expectedMessageCount(1);
        mockAmsValidationEndpoint.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockAmsValidationEndpoint.expectedHeaderReceived("Content-Type", "application/json");

        Exchange exchange = camelContext.getEndpoint("direct:transfer-validation").createExchange();
        exchange.setProperty(TRANSACTION_ID, "af71cac2-ec74-4bd1-a584-ea7b72d73982");
        Object mockedConfirmationRequest = getValidationPayBillObject();

        exchange.getIn().setBody(mockedConfirmationRequest);
        // Trigger the route
        Exchange result = fluentProducerTemplate.to("direct:transfer-validation").withExchange(exchange).send();

        // Verify the expectations
        mockAmsValidationEndpoint.assertIsSatisfied();

        Exchange exchangeResult = mockAmsValidationEndpoint.getExchanges().get(0);

        String receivedBody = exchangeResult.getIn().getBody(String.class);
        assertNotNull(result);
        assertTrue(receivedBody.contains("\"Currency\":\"RWF\""));
        assertTrue(receivedBody.contains("\"Amount\":1000"));
        assertTrue(receivedBody.contains("\"Account\":\"324567\""));
        assertTrue(receivedBody.contains("\"RemoteTransactionId\":\"670d65bd-4efd-4a6c-ae2c-7fdaa8cb4d60\""));
        assertTrue(receivedBody.contains("\"PhoneNumber\":\"12345678\""));
    }

    @DisplayName("When the transfer validation route is called, it should return the expected response.")
    @Test
    void testTransferValidationRouteReturnsExpectedResponse() throws Exception {
        AdviceWith.adviceWith(camelContext, "transfer-validation", routeBuilder -> {
            routeBuilder
                    .weaveByToUri(
                            fineractBaseUrl + validationEndpoint + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                                    + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                    .replace().to(mockAmsValidationEndpoint);
        });

        mockAmsValidationEndpoint.expectedMessageCount(1);
        mockAmsValidationEndpoint.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockAmsValidationEndpoint.expectedHeaderReceived("Content-Type", "application/json");

        Exchange exchange = camelContext.getEndpoint("direct:transfer-validation").createExchange();
        exchange.setProperty(TRANSACTION_ID, "af71cac2-ec74-4bd1-a584-ea7b72d73982");
        Object mockedConfirmationRequest = getValidationPayBillObject();
        exchange.setProperty(CHANNEL_REQUEST, getValidationJsonObject());

        exchange.getIn().setBody(mockedConfirmationRequest);
        // Trigger the route
        Exchange result = fluentProducerTemplate.to("direct:transfer-validation").withExchange(exchange).send();

        // Verify the expectations
        mockAmsValidationEndpoint.assertIsSatisfied();

        Exchange exchangeResult = mockAmsValidationEndpoint.getExchanges().get(0);

        String receivedBody = exchangeResult.getIn().getBody(String.class);
        assertNotNull(result);
        assertTrue(receivedBody.contains("\"Currency\":\"RWF\""));
        assertTrue(receivedBody.contains("\"Amount\":42.0"));
        assertTrue(receivedBody.contains("\"Account\":\"25598208\""));
        assertTrue(receivedBody.contains("\"RemoteTransactionId\":\"af71cac2-ec74-4bd1-a584-ea7b72d73982\""));
        assertTrue(receivedBody.contains("\"PhoneNumber\":\"250788000000\""));
        assertTrue(receivedBody.contains("\"getAccountDetails\":false"));
    }

    private JSONObject getValidationPayBillObject() {
        String chanelRequest = """
                    {
                        "secondaryIdentifier": {
                            "key": "foundationalId",
                            "value": "12345678"
                        },
                        "primaryIdentifier": {
                            "key": "MSISDN",
                            "value": "324567"
                        },
                        "customData": [
                         {
                                    "key": "transactionId",
                                    "value": "670d65bd-4efd-4a6c-ae2c-7fdaa8cb4d60"
                                },
                                {
                                    "key": "currency",
                                    "value": "RWF"
                                },
                                {
                                    "key": "amount",
                                    "value": "1000"
                                }
                        ]
                    }
                """;
        return new JSONObject(chanelRequest);
    }

    private JSONObject getValidationJsonObject() {
        String chanelRequest = """
                    {
                    "payee": {
                        "partyIdInfo": {
                            "partyIdType": "FOUNDATIONALID",
                            "partyIdentifier":"25598208"
                            }
                        },
                    "amount": {
                        "amount":"42.00",
                        "currency":"RWF"
                        },
                    "payer": {
                        "partyIdInfo": {
                            "partyIdType":"MSISDN",
                            "partyIdentifier":"250788000000"
                            }
                        }
                    }
                """;
        return new JSONObject(chanelRequest);
    }

    private JSONObject getSettlementJsonObject() {
        String chanelRequest = """
                    {
                    "payee": {
                        "partyIdInfo": {
                            "partyIdType": "FOUNDATIONALID",
                            "partyIdentifier":"25598208"
                            }
                        },
                    "amount": {
                        "amount":"42.00",
                        "currency":"RWF"
                        },
                    "payer": {
                        "partyIdInfo": {
                            "partyIdType":"MSISDN",
                            "partyIdentifier":"250788000000"
                            }
                        }
                    }
                """;
        return new JSONObject(chanelRequest);
    }

    private JSONObject getUseWorkflowIdAsTransactionIdChannelRequest() {
        String chanelRequest = """
                    {
                    "useWorkflowIdAsTransactionId": true,
                    "workflowId": "b3156ea5-3240-4661-8bb7-7c6abd35380d",
                    "externalId": "ef01bdd4-439e-4a70-b8da-cc81f2dd34d0",
                    "payee": {
                        "partyIdInfo": {
                            "partyIdType": "FOUNDATIONALID",
                            "partyIdentifier":"25598208"
                            }
                        },
                    "amount": {
                        "amount":"42.00",
                        "currency":"RWF"
                        },
                    "payer": {
                        "partyIdInfo": {
                            "partyIdType":"MSISDN",
                            "partyIdentifier":"250788000000"
                            }
                        }
                    }
                """;
        return new JSONObject(chanelRequest);
    }

    private JSONObject getUseWorkflowIdAsTransactionIdChannelRequestWithUseWorkFlowAsFalse() {
        String chanelRequest = """
                    {
                    "useWorkflowIdAsTransactionId": false,
                    "workflowId": "b3156ea5-3240-4661-8bb7-7c6abd35380d",
                    "externalId": "ef01bdd4-439e-4a70-b8da-cc81f2dd34d0",
                    "payee": {
                        "partyIdInfo": {
                            "partyIdType": "FOUNDATIONALID",
                            "partyIdentifier":"25598208"
                            }
                        },
                    "amount": {
                        "amount":"42.00",
                        "currency":"RWF"
                        },
                    "payer": {
                        "partyIdInfo": {
                            "partyIdType":"MSISDN",
                            "partyIdentifier":"250788000000"
                            }
                        }
                    }
                """;
        return new JSONObject(chanelRequest);
    }

}
