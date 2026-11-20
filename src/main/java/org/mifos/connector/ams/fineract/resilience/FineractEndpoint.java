package org.mifos.connector.ams.fineract.resilience;

/**
 * Fineract endpoints called through {@link FineractHttpCaller}; each endpoint and host gets its own circuit breaker.
 */
public enum FineractEndpoint {

    /** POST /paymentHub/verification: validates payer and account, moves no money. */
    VERIFICATION("verification"),
    /** GET /payments/validations/{transactionId}. */
    CLIENT_DETAILS("client-details"),
    /** POST /paymentHub/confirmation: posts the settlement. */
    CONFIRMATION("confirmation"),
    /** POST /paymentHub/payments: validation and settlement in one call (virtual accounts). */
    PAYMENTS("payments");

    private final String id;

    FineractEndpoint(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
