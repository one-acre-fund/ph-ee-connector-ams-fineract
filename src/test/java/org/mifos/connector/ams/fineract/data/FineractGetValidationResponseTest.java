package org.mifos.connector.ams.fineract.data;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mifos.connector.common.gsma.dto.CustomData;

class FineractGetValidationResponseTest {

    @DisplayName("Converts all fields of FineractGetValidationResponse to CustomData list")
    @Test
    void test_convert_all_fields_to_custom_data() {
        FineractGetValidationResponse response = new FineractGetValidationResponse("txn123", new BigDecimal("100.00"),
                "acc123", "1234567890", "providerX", "John", "Doe", "clientAcc123", "0987654321");

        List<CustomData> customDataList = FineractGetValidationResponse.convertToCustomData(response);

        assertEquals(9, customDataList.size());
        assertEquals("txn123", customDataList.get(0).getValue());
        assertEquals(new BigDecimal("100.00"), customDataList.get(1).getValue());
        assertEquals("acc123", customDataList.get(2).getValue());
        assertEquals("1234567890", customDataList.get(3).getValue());
        assertEquals("providerX", customDataList.get(4).getValue());
        assertEquals("John", customDataList.get(5).getValue());
        assertEquals("Doe", customDataList.get(6).getValue());
        assertEquals("clientAcc123", customDataList.get(7).getValue());
        assertEquals("0987654321", customDataList.get(8).getValue());
    }
}
