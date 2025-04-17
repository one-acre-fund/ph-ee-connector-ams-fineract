package org.mifos.connector.ams.fineract.camel.route;

import static org.mifos.connector.ams.fineract.camel.config.CamelProperties.*;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.CONFIRMATION_RECEIVED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.CUSTOM_DATA;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.EXTERNAL_ID;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSFER_SETTLEMENT_FAILED;

import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mifos.connector.ams.fineract.data.FineractConfirmationRequestDto;
import org.mifos.connector.ams.fineract.data.FineractGetValidationResponse;
import org.mifos.connector.ams.fineract.data.FineractRequestDto;
import org.mifos.connector.ams.fineract.util.ConnectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Route handlers for Fineract flow. */
@Slf4j
@Component
public class FineractRouteBuilder extends RouteBuilder {

    @Value("${fineract.base-url}")
    private String fineractBaseUrl;

    @Value("${fineract.endpoint.validation}")
    private String validationEndpoint;

    @Value("${fineract.endpoint.confirmation}")
    private String confirmationEndpoint;

    @Value("${fineract.endpoint.client-details}")
    private String clientDetailsEndpoint;

    @Value("${ams.timeout}")
    private Integer amsTimeout;

    @Override
    public void configure() throws Exception {

        onException(Exception.class).routeId("transfer-validation-base").handled(true).setBody(exchange -> {
            // processing exception case
            exchange.setProperty(PARTY_LOOKUP_FAILED, true);
            exchange.setProperty(ERROR_INFORMATION,
                    "Exception occurred in route transfer-validation-base: ${exception.message}");
            return exchange.getIn().getBody();
        }).log(LoggingLevel.ERROR, "Exception occurred in route transfer-validation-base: ${exception.message}")
                .to("direct:error-handler");

        onException(Exception.class).routeId("transfer-settlement-base").handled(true).setBody(exchange -> {
            // processing exception case
            exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, true);
            return exchange.getIn().getBody();
        }).log(LoggingLevel.ERROR, "Exception occurred in route transfer-settlement-base: ${exception.message}")
                .to("direct:error-handler");

        from("direct:error-handler").log(LoggingLevel.ERROR, "Error handler route: ${body}");

        from("direct:transfer-validation-base").id("transfer-validation-base")
                .log(LoggingLevel.INFO, "## Starting transfer Validation base route").to("direct:transfer-validation")
                .choice().when(header(CAMEL_HTTP_RESPONSE_CODE).isEqualTo("200"))
                .log(LoggingLevel.INFO,
                        "Fineract validation successful for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .process(exchange -> {
                    // processing success case
                    exchange.setProperty(PARTY_LOOKUP_FAILED, false);
                    exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME,
                            exchange.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
                    exchange.setProperty(TRANSACTION_ID, exchange.getProperty(TRANSACTION_ID));
                    exchange.setProperty(AMOUNT_VARIABLE_NAME, exchange.getProperty(AMOUNT_VARIABLE_NAME));
                    exchange.setProperty(CURRENCY_VARIABLE_NAME, exchange.getProperty(CURRENCY_VARIABLE_NAME));
                    exchange.setProperty(MSISDN_VARIABLE_NAME, exchange.getProperty(MSISDN_VARIABLE_NAME));
                    log.debug("Fineract Validation Success");
                }).choice().when(exchangeProperty(GET_ACCOUNT_DETAILS_FLAG).isEqualTo(true))
                .to("direct:get-client-details").unmarshal()
                .json(JsonLibrary.Jackson, FineractGetValidationResponse[].class).process(e -> {
                    log.debug("Fineract get client details api response: {}", e.getIn().getBody());
                    FineractGetValidationResponse[] clientDetailsResponse = e.getIn()
                            .getBody(FineractGetValidationResponse[].class);
                    if (clientDetailsResponse != null && clientDetailsResponse.length > 0) {
                        FineractGetValidationResponse clientDetails = clientDetailsResponse[0];
                        e.setProperty(CLIENT_NAME_VARIABLE_NAME,
                                clientDetails.getClientFirstname() + " " + clientDetails.getClientLastname());
                        e.setProperty(CUSTOM_DATA_VARIABLE_NAME,
                                FineractGetValidationResponse.convertToCustomData(clientDetails));
                    }
                }).endChoice().otherwise()
                .log(LoggingLevel.ERROR,
                        "Fineract validation unsuccessful for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .process(exchange -> {
                    // processing unsuccessful case
                    exchange.setProperty(PARTY_LOOKUP_FAILED, true);
                    exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME,
                            exchange.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
                    exchange.setProperty(TRANSACTION_ID, exchange.getProperty(TRANSACTION_ID));
                    exchange.setProperty(AMOUNT_VARIABLE_NAME, exchange.getProperty(AMOUNT_VARIABLE_NAME));
                    exchange.setProperty(CURRENCY_VARIABLE_NAME, exchange.getProperty(CURRENCY_VARIABLE_NAME));
                    exchange.setProperty(MSISDN_VARIABLE_NAME, exchange.getProperty(MSISDN_VARIABLE_NAME));
                    log.debug("Fineract Validation Failure");
                });
        from("direct:transfer-validation").id("transfer-validation")
                .log(LoggingLevel.INFO, "## Starting transfer Validation route").removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json")).setBody(exchange -> {
                    FineractRequestDto verificationRequestDto;
                    if (exchange.getProperty(CHANNEL_REQUEST) != null) {
                        JSONObject channelRequest = (JSONObject) exchange.getProperty(CHANNEL_REQUEST);
                        JSONArray customData = exchange.getProperty(CUSTOM_DATA, JSONArray.class);
                        verificationRequestDto = FineractRequestDto.fromChannelRequest(channelRequest,
                                exchange.getProperty(TRANSACTION_ID, String.class), customData);
                    } else {
                        JSONObject payBillRequest = new JSONObject(exchange.getIn().getBody(String.class));

                        verificationRequestDto = FineractRequestDto.convertPayBillPayloadToAmsPayload(payBillRequest);
                        exchange.setProperty(TRANSACTION_ID, verificationRequestDto.getRemoteTransactionId());
                        exchange.setProperty(AMOUNT_VARIABLE_NAME, verificationRequestDto.getAmount());
                        exchange.setProperty(CURRENCY_VARIABLE_NAME, verificationRequestDto.getCurrency());
                        exchange.setProperty(MSISDN_VARIABLE_NAME, verificationRequestDto.getPhoneNumber());
                        exchange.setProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME,
                                exchange.getProperty(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME));
                    }
                    log.info("Fineract validation request DTO for transaction {} sent on {}: \n{}",
                            verificationRequestDto.getRemoteTransactionId(), Instant.now(), verificationRequestDto);
                    exchange.setProperty(GET_ACCOUNT_DETAILS_FLAG, verificationRequestDto.isGetAccountDetails());
                    return verificationRequestDto;
                }).marshal().json(JsonLibrary.Jackson)
                .toD(getValidationUrl() + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                        + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                .log(LoggingLevel.INFO,
                        "Received Fineract validation response for " + "transaction ${exchangeProperty."
                                + TRANSACTION_ID
                                + "} on ${header.Date} with status: ${header.CamelHttpResponseCode}. Body: \n ${body}")
                .setProperty(VALIDATION_RESPONSE_BODY).simple("${body}");

        from("direct:transfer-settlement-base").id("transfer-settlement-base")
                .log(LoggingLevel.INFO, "## Transfer Settlement route").to("direct:transfer-settlement").choice()
                .when(header(CAMEL_HTTP_RESPONSE_CODE).isEqualTo("200"))
                .log(LoggingLevel.INFO,
                        "Fineract settlement successful for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .process(exchange -> {
                    // processing success case
                    // check if actual transaction was also successful
                    Boolean transactionFailed = exchange.getProperty(TRANSACTION_FAILED, Boolean.class);
                    boolean transferSettlementFailed = !Boolean.FALSE.equals(transactionFailed);

                    // When a direct confirmation request is received, then it's a paybill transaction, and we don't
                    // have to worry about the transactionFailed flag above.
                    if (Boolean.TRUE.equals(exchange.getProperty(CONFIRMATION_RECEIVED, Boolean.class))) {
                        transferSettlementFailed = false;
                    }
                    exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, transferSettlementFailed);
                }).otherwise()
                .log(LoggingLevel.ERROR,
                        "Fineract settlement unsuccessful for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .process(exchange ->
                // processing unsuccessful case
                exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, true));

        from("direct:transfer-settlement").id("transfer-settlement")
                .log(LoggingLevel.INFO, "## Starting transfer settlement route").removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json")).setBody(exchange -> {
                    FineractConfirmationRequestDto confirmationRequestDto;
                    if (exchange.getProperty(CHANNEL_REQUEST) != null) {
                        JSONObject channelRequest = (JSONObject) exchange.getProperty(CHANNEL_REQUEST);

                        String transactionId = exchange.getProperty(TRANSACTION_ID, String.class);
                        // TNM flow: since the validation flow uses workflowId in the AMS validation call, we need to
                        // use it here as well
                        Object useWorkflowIdAsTransactionIdObj = channelRequest.has("useWorkflowIdAsTransactionId")
                                ? channelRequest.get("useWorkflowIdAsTransactionId")
                                : null;
                        if (useWorkflowIdAsTransactionIdObj != null && (Boolean) useWorkflowIdAsTransactionIdObj
                                && channelRequest.has("workflowId")
                                && channelRequest.get("workflowId") instanceof String workflowId) {
                            transactionId = workflowId;
                        }

                        String externalId = exchange.getProperty(EXTERNAL_ID, String.class);
                        externalId = Objects.nonNull(externalId) ? externalId : channelRequest.getString(EXTERNAL_ID);
                        confirmationRequestDto = FineractConfirmationRequestDto.fromChannelRequest(channelRequest,
                                transactionId);
                        confirmationRequestDto.setStatus("successful");
                        confirmationRequestDto.setReceiptId(externalId);

                    } else {
                        JSONObject payBillRequest = new JSONObject(exchange.getIn().getBody(String.class));

                        confirmationRequestDto = FineractConfirmationRequestDto
                                .convertPayBillPayloadToAmsPayload(payBillRequest);
                        exchange.setProperty(TRANSACTION_ID, confirmationRequestDto.getRemoteTransactionId());
                    }
                    log.info("Fineract confirmation request DTO for transaction {} sent on {}: \n{}",
                            confirmationRequestDto.getRemoteTransactionId(), Instant.now(), confirmationRequestDto);
                    return confirmationRequestDto;
                }).marshal().json(JsonLibrary.Jackson)
                .toD(getConfirmationUrl() + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                        + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                .log(LoggingLevel.INFO,
                        "Received Fineract confirmation response for " + "transaction ${exchangeProperty."
                                + TRANSACTION_ID
                                + "} on ${header.Date} with status: ${header.CamelHttpResponseCode}. Body: \n ${body}");

        from("direct:get-client-details").id("get-client-details")
                .log(LoggingLevel.INFO, "## Starting get client details route")
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("fineract-platform-tenantid", constant("default")).process(e -> {
                    String transactionId = e.getProperty(TRANSACTION_ID, String.class);
                    e.getIn().setHeader(TRANSACTION_ID, transactionId);
                    e.setProperty(TRANSACTION_ID, transactionId);
                    e.getIn().setHeader("clientDetailsUrl", getClientDetailsUrl());
                    log.info("Fineract client details request for transaction {} sent on {}", transactionId,
                            Instant.now());
                }).log(" ## Transaction id: ${header.transactionId}")
                .log(" ## Transaction id as property: ${exchangeProperty.transactionId}")
                .toD("${header.clientDetailsUrl}/${header.transactionId}?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                        + ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                .log(LoggingLevel.INFO, "Headers: ${headers}")
                .log(LoggingLevel.INFO, "Status: ${header.CamelHttpResponseCode}").log(LoggingLevel.INFO,
                        "Received Fineract client details response for " + "transaction ${exchangeProperty."
                                + TRANSACTION_ID
                                + "} on ${header.Date} with status: ${header.CamelHttpResponseCode}. Body: ${body}");
    }

    /**
     * Combines Fineract base url and the validation endpoint.
     *
     * @return the full url to be used in Fineract confirmation requests
     */
    private String getValidationUrl() {
        return fineractBaseUrl + validationEndpoint;
    }

    /**
     * Combines Fineract base url and the confirmation endpoint.
     *
     * @return the full url to be used in Fineract confirmation requests
     */
    private String getConfirmationUrl() {
        return fineractBaseUrl + confirmationEndpoint;
    }

    /**
     * Combines Fineract base url and the get client details endpoint.
     *
     * @return the full url to be used in Fineract get client details requests
     */
    private String getClientDetailsUrl() {
        return fineractBaseUrl + clientDetailsEndpoint;
    }
}
