package org.mifos.connector.ams.fineract.data;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FineractConfirmationRequestDtoTest {

    @DisplayName("Successfully creates a FineractConfirmationRequestDto from valid channelRequest and transactionId")
    @Test
    void test_create_fineract_confirmation_request_dto_success() {
        JSONObject channelRequest = new JSONObject();
        channelRequest.put("payer",
                new JSONObject().put("partyIdInfo", new JSONObject().put("partyIdentifier", "12345")));
        channelRequest.put("payee",
                new JSONObject().put("partyIdInfo", new JSONObject().put("partyIdentifier", "67890")));
        channelRequest.put("amount", new JSONObject().put("amount", "100.00").put("currency", "USD"));
        String transactionId = "txn123";

        FineractConfirmationRequestDto dto = FineractConfirmationRequestDto.fromChannelRequest(channelRequest,
                transactionId);

        assertNotNull(dto);
        assertEquals("12345", dto.getPhoneNumber());
        assertEquals("67890", dto.getAccount());
        assertEquals(new BigDecimal("100.00"), dto.getAmount());
        assertEquals("USD", dto.getCurrency());
        assertEquals("txn123", dto.getRemoteTransactionId());
    }

    @DisplayName("Converts valid JSON payload to FineractConfirmationRequestDto successfully")
    @Test
    void test_convert_valid_json_payload() {
        JSONObject payload = new JSONObject();
        payload.put("primaryIdentifier", new JSONObject().put("value", "account123"));
        payload.put("secondaryIdentifier", new JSONObject().put("value", "wallet123"));
        JSONArray customData = new JSONArray();
        customData.put(new JSONObject().put("transactionId", "txn123"));
        customData.put(new JSONObject().put("amount", "100.00"));
        customData.put(new JSONObject().put("currency", "USD"));
        payload.put("customData", customData);

        FineractConfirmationRequestDto result = FineractConfirmationRequestDto
                .convertPayBillPayloadToAmsPayload(payload);

        assertNotNull(result);
        assertEquals("account123", result.getAccount());
        assertEquals("wallet123", result.getPhoneNumber());
    }

}
