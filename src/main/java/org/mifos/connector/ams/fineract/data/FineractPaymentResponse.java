package org.mifos.connector.ams.fineract.data;

/**
 * DTO representing a response from the Fineract AMS payment API.
 *
 * @param status
 *            the HTTP status code of the response
 * @param message
 *            the message associated with the response
 * @param transactionId
 *            the transaction ID of the payment
 * @param externalId
 *            the external ID associated with the payment
 * @param accountNumber
 *            the account number of the client associated with the payment
 */
public record FineractPaymentResponse(int status, String message, String transactionId, String externalId,
        String accountNumber) {
}
