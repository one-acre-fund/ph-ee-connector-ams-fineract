package org.mifos.connector.ams.fineract.camel.route;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mifos.connector.ams.fineract.camel.config.CamelProperties.*;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_ID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.camel.*;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mifos.connector.ams.fineract.FineractConnectorApplicationSetUp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FineractPayBillRouteBuilderTest extends FineractConnectorApplicationSetUp {

    @Autowired
    @JsonIgnore
    private CamelContext camelContext;

    @EndpointInject("mock:direct:transfer-validation-base")
    protected MockEndpoint mockTransferValidationBase;

    @DisplayName("Test pay bill validation flow returns 200 and correct response")
    @Test
    void testPayBillValidationReturns200AndValidResponse() throws Exception {
        AdviceWith.adviceWith(camelContext, "validate-user", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-validation-base").replace().to(mockTransferValidationBase);
        });
        mockTransferValidationBase.whenAnyExchangeReceived(ex -> {
            ex.setProperty(TRANSACTION_ID, 123425);
            ex.setProperty(PARTY_LOOKUP_FAILED, false);
            ex.setProperty("amount", 42.0);
            ex.setProperty("msisdn", "test");
            ex.setProperty("currency", "RWF");
            ex.setProperty("msisdn", "250788000000");
            ex.setProperty(CLIENT_NAME_VARIABLE_NAME, "John Doe");
            ex.setProperty(CUSTOM_DATA_VARIABLE_NAME, "customData");
            ex.setProperty(VALIDATION_RESPONSE_BODY, "{ \"message\": \"Validation successful\" }");
        });
        given().baseUri("http://0.0.0.0:5093").contentType("application/json").body("{ \"transactionId\": \"123425\" }")
                .header(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "9876").when()
                .post("/api/v1/paybill/validate/fineract").then().statusCode(200).body("amount", equalTo(42))
                .body("reconciled", equalTo(true)).body("amsName", equalTo("fineract"))
                .body("clientName", equalTo("John Doe")).body("currency", equalTo("RWF"))
                .body("customData", equalTo("customData")).body("accountHoldingInstitutionId", equalTo("9876"))
                .body("msisdn", equalTo("250788000000")).body("transactionId", equalTo(123425))
                .body("message", equalTo("Validation successful"));
    }

    @DisplayName("Test pay bill validation flow when validation fails with default message")
    @Test
    void testFailedPayBillValidationReturnsDefaultMessage() throws Exception {
        AdviceWith.adviceWith(camelContext, "validate-user", routeBuilder -> {
            routeBuilder.weaveByToUri("direct:transfer-validation-base").replace().to(mockTransferValidationBase);
        });
        mockTransferValidationBase.whenAnyExchangeReceived(ex -> {
            ex.setProperty(TRANSACTION_ID, 123425);
            ex.setProperty(PARTY_LOOKUP_FAILED, true);
        });
        given().baseUri("http://0.0.0.0:5093").contentType("application/json").body("{ \"transactionId\": \"123425\" }")
                .header(ACCT_HOLDING_INSTITUTION_ID_VARIABLE_NAME, "9876").when()
                .post("/api/v1/paybill/validate/fineract").then().body("reconciled", equalTo(false))
                .body("amsName", equalTo("fineract")).body("accountHoldingInstitutionId", equalTo("9876"))
                .body("transactionId", equalTo(123425))
                .body("message", equalTo("Error occurred while validating client"));
    }
}
