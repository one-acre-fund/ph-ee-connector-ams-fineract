package org.mifos.connector.ams.fineract.util;

import static org.junit.jupiter.api.Assertions.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectionUtilsTest {

    @DisplayName("Returns the correct value when the key matches an entry in JSONArray")
    @Test
    void test_returns_correct_value_for_matching_key() {
        JSONArray customData = new JSONArray();
        JSONObject item1 = new JSONObject();
        item1.put("key", "name");
        item1.put("value", "John Doe");
        customData.put(item1);

        String result = ConnectionUtils.convertCustomData(customData, "name");
        assertEquals("John Doe", result);
    }

    @DisplayName("Returns the correct value for a matching key in JSONArray")
    @Test
    void test_returns_correct_value_for_matching_key_multiple_items() {
        JSONArray customData = new JSONArray();
        JSONObject item1 = new JSONObject();
        item1.put("key", "name");
        item1.put("value", "John Doe");
        customData.put(item1);

        JSONObject item2 = new JSONObject();
        item2.put("key", "age");
        item2.put("value", 30);
        customData.put(item2);

        String result = ConnectionUtils.convertCustomData(customData, "name");
        assertEquals("John Doe", result);
    }

    // Handles empty JSONArray without errors
    @Test
    void test_handles_empty_jsonarray() {
        JSONArray customData = new JSONArray();

        String result = ConnectionUtils.convertCustomData(customData, "name");
        assertNull(result);
    }

    @DisplayName("Handles empty JSONArray without errors")
    @Test
    void test_handles_empty_jsonarray_any_key() {
        JSONArray customData = new JSONArray();
        JSONObject item1 = new JSONObject();
        item1.put("key", "name");
        item1.put("value", "John Doe");
        customData.put(item1);
        String result = ConnectionUtils.convertCustomData(customData, null);
        assertNull(result);
    }

    @DisplayName("Returns error description when valid JSON with known keys is provided")
    @Test
    void test_returns_error_description_with_known_keys() {
        String json = "{\"error\":\"Sample error message\"}";
        String result = ConnectionUtils.parseErrorDescriptionFromJsonPayload(json);
        assertEquals("Sample error message", result);
    }

    @DisplayName("Handles JSON with none of the specified keys gracefully")
    @Test
    void test_handles_json_with_no_specified_keys() {
        String json = "{\"unknownKey\":\"Some value\"}";
        String result = ConnectionUtils.parseErrorDescriptionFromJsonPayload(json);
        assertEquals("Internal Server Error", result);
    }

    @DisplayName("Returns Internal Server Error for null input")
    @Test
    void test_null_input_returns_internal_server_error() {
        String result = ConnectionUtils.parseErrorDescriptionFromJsonPayload(null);
        assertEquals("Internal Server Error", result);
    }

    @DisplayName("Returns Internal Server Error for empty string input")
    @Test
    void test_empty_string_input_returns_internal_server_error() {
        String result = ConnectionUtils.parseErrorDescriptionFromJsonPayload("");
        assertEquals("Internal Server Error", result);
    }

    @DisplayName("Logs error message when JSON parsing fails")
    @Test
    void test_logs_error_message_on_json_parsing_failure() {
        String malformedJson = "{invalidJson}";
        String result = ConnectionUtils.parseErrorDescriptionFromJsonPayload(malformedJson);
        assertEquals("Internal Server Error", result);
    }

    @DisplayName("Returns a valid DSL string when a positive timeout is provided")
    @Test
    void test_positive_timeout() {
        int timeout = 5000;
        String expectedDsl = "httpClient.connectTimeout=5000&httpClient.connectionRequestTimeout=5000&httpClient.socketTimeout=5000";
        String actualDsl = ConnectionUtils.getConnectionTimeoutDsl(timeout);
        assertEquals(expectedDsl, actualDsl);
    }

}
