package org.mifos.connector.ams.fineract.zeebe;

/** Contains variables referenced in zeebe. */
public class ZeebeVariables {

    private ZeebeVariables() {}

    public static final String TRANSACTION_ID = "transactionId";
    public static final String GET_TRANSACTION_STATUS_RESPONSE_CODE = "getTransactionStatusHttpCode";
    public static final String PARTY_LOOKUP_FAILED = "partyLookupFailed";
    public static final String TRANSFER_SETTLEMENT_FAILED = "transferSettlementFailed";
    public static final String ERROR_INFORMATION = "errorInformation";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_DESCRIPTION = "errorDescription";
    public static final String EXTERNAL_ID = "externalId";
    public static final String TRANSACTION_FAILED = "transactionFailed";
    public static final String FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME = "transfer-validation-fineract";
    public static final String FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME = "transfer-settlement-fineract";
    public static final String CUSTOM_DATA = "customData";
    public static final String CONFIRMATION_RECEIVED = "confirmationReceived";
    public static final String VALIDATION_AND_SETTLEMENT_WORKER_NAME = "transfer-validation-and-settlement-fineract";
}
