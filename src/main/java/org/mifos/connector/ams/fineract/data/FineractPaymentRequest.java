package org.mifos.connector.ams.fineract.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO representing a request to process a payment in the Fineract AMS.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FineractPaymentRequest extends FineractRequestDto {

    @JsonProperty("AccountProvider")
    private String accountProvider;

    @JsonProperty("ReceiptId")
    private String receiptId;

    @Override
    public String toString() {
        return "FineractPaymentRequest{" + "remoteTransactionId='" + getRemoteTransactionId() + '\'' + ", phoneNumber='"
                + getPhoneNumber() + '\'' + ", account='" + getAccount() + '\'' + ", amount=" + getAmount()
                + ", currency='" + getCurrency() + '\'' + ", loanId=" + getLoanId() + ", accountProvider='"
                + accountProvider + '\'' + ", receiptId='" + receiptId + '\'' + '}';
    }

    /**
     * Creates a {@link FineractPaymentRequest} from a {@link ChannelRequest}. This method is specifically designed for
     * virtual accounts transactions where the actual Fineract account isn't yet known.
     *
     * @param channelRequest
     *            {@link ChannelRequest}
     * @param transactionId
     *            the transaction ID of the request
     * @param receiptId
     *            the receipt ID of the request
     * @return a {@link FineractPaymentRequest} object populated with data from the channel request
     */
    public static FineractPaymentRequest fromChannelRequestForVirtualAccounts(ChannelRequest channelRequest,
            String transactionId, String receiptId) {
        FineractPaymentRequest request = new FineractPaymentRequest();
        request.setRemoteTransactionId(transactionId);
        request.setReceiptId(receiptId);
        // Use the virtual account in place of the actual fineract account
        request.setAccount(channelRequest.getPayer().getPartyIdInfo().getPartyIdentifier());
        request.setPhoneNumber(channelRequest.getPayee().getPartyIdInfo().getPartyIdentifier());
        request.setAmount(channelRequest.getAmount().getAmount());
        request.setCurrency(channelRequest.getAmount().getCurrency());
        request.setAccountProvider(channelRequest.getTransactionType().getScenario());
        return request;
    }
}
