package org.mifos.connector.ams.fineract.data;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO representing a generic request body from the channel connector.
 */
@Getter
@Setter
public class ChannelRequest {

    private PartyData payer;
    private PartyData payee;
    private Amount amount;
    private TransactionType transactionType;

    @Getter
    @Setter
    public static class PartyData {

        private PartyIdInfo partyIdInfo;
    }

    @Getter
    @Setter
    public static class PartyIdInfo {

        private String partyIdType;
        private String partyIdentifier;
    }

    @Getter
    @Setter
    public static class Amount {

        private BigDecimal amount;
        private String currency;
    }

    @Getter
    @Setter
    public static class TransactionType {

        private String scenario;
    }
}
