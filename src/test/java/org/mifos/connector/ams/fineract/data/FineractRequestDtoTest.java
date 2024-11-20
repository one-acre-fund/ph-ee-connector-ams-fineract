package org.mifos.connector.ams.fineract.data;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FineractRequestDtoTest {

    @DisplayName("Converts valid channelRequest and customData into FineractRequestDto correctly")
    @Test
    void test_valid_channel_request_conversion() {
        JSONObject channelRequest = new JSONObject();
        channelRequest.put("payer",
                new JSONObject().put("partyIdInfo", new JSONObject().put("partyIdentifier", "1234567890")));
        channelRequest.put("payee",
                new JSONObject().put("partyIdInfo", new JSONObject().put("partyIdentifier", "0987654321")));
        channelRequest.put("amount", "100.00");
        channelRequest.put("currency", "USD");

        JSONArray customData = new JSONArray();
        customData.put(new JSONObject().put("loanId", "12345"));
        customData.put(new JSONObject().put("getAccountDetails", "true"));

        FineractRequestDto result = FineractRequestDto.fromChannelRequest(channelRequest, "txn123", customData);

        assertEquals("1234567890", result.getPhoneNumber());
        assertEquals("0987654321", result.getAccount());
        assertEquals(new BigDecimal("100.00"), result.getAmount());
        assertEquals("USD", result.getCurrency());
    }

    @DisplayName("Converts valid JSON payload to FineractRequestDto correctly")
    @Test
    void test_convert_valid_json_payload() {
        JSONObject payload = new JSONObject();
        JSONObject primaryIdentifier = new JSONObject();
        primaryIdentifier.put("value", "account123");
        JSONObject secondaryIdentifier = new JSONObject();
        secondaryIdentifier.put("value", "msisdn123");
        JSONArray customData = new JSONArray();
        JSONObject transactionIdData = new JSONObject();
        transactionIdData.put("key", "transactionId");
        transactionIdData.put("value", "txn123");
        customData.put(transactionIdData);
        JSONObject currencyData = new JSONObject();
        currencyData.put("key", "currency");
        currencyData.put("value", "USD");
        customData.put(currencyData);
        JSONObject amountData = new JSONObject();
        amountData.put("key", "amount");
        amountData.put("value", "100.00");
        customData.put(amountData);

        payload.put("primaryIdentifier", primaryIdentifier);
        payload.put("secondaryIdentifier", secondaryIdentifier);
        payload.put("customData", customData);

        FineractRequestDto result = FineractRequestDto.convertPayBillPayloadToAmsPayload(payload);

        assertEquals("account123", result.getAccount());
        assertEquals(new BigDecimal("100.00"), result.getAmount());
        assertEquals("USD", result.getCurrency());
        assertEquals("txn123", result.getRemoteTransactionId());
        assertEquals("msisdn123", result.getPhoneNumber());
    }

    @DisplayName("Handles null or missing customData gracefully")
    @Test
    void test_handle_null_or_missing_custom_data() {
        JSONObject payload = new JSONObject();
        JSONObject primaryIdentifier = new JSONObject();
        primaryIdentifier.put("value", "account123");
        JSONObject secondaryIdentifier = new JSONObject();
        secondaryIdentifier.put("value", "msisdn123");

        payload.put("primaryIdentifier", primaryIdentifier);
        payload.put("secondaryIdentifier", secondaryIdentifier);
        payload.put("customData", new JSONArray());

        FineractRequestDto result = FineractRequestDto.convertPayBillPayloadToAmsPayload(payload);

        assertEquals("account123", result.getAccount());
        assertEquals(BigDecimal.ZERO, result.getAmount());
        assertNull(result.getCurrency());
        assertNull(result.getRemoteTransactionId());
        assertEquals("msisdn123", result.getPhoneNumber());
    }

}
