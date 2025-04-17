package org.mifos.connector.ams.fineract.data;

/**
 * Fineract validation response DTO.
 *
 * @param message
 *            the message
 * @param transactionId
 *            the transaction ID
 */
public record FineractValidationResponse(String message, String transactionId) {
}
