package org.mifos.connector.ams.fineract.util;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.util.json.JsonObject;
import org.json.JSONArray;
import org.json.JSONObject;

/** Contains utility methods for connection. */
@Slf4j
public class ConnectionUtils {

    private ConnectionUtils() {}

    /**
     * Parse the error description from the JSON.
     *
     * @param errorJson
     *            the JSON containing the error
     * @return the error description
     */
    public static String parseErrorDescriptionFromJsonPayload(String errorJson) {
        if (errorJson == null || errorJson.isEmpty()) {
            return "Internal Server Error";
        }
        try {
            JsonObject jsonObject = (new Gson()).fromJson(errorJson, JsonObject.class);
            String[] keyList = { "Message", "error", "errorDescription", "errorMessage", "description" };
            for (String s : keyList) {
                String data = jsonObject.getString(s);
                if (data != null && !data.isEmpty()) {
                    return data;
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return "Internal Server Error";
    }

    /**
     * Convert custom data.
     *
     * @param customData
     *            {@link JSONArray}
     * @param key
     *            the custom data key
     * @return the custom data value
     */
    public static String convertCustomData(JSONArray customData, String key) {
        for (Object obj : customData) {
            JSONObject item = (JSONObject) obj;
            try {
                String filter = item.getString("key");
                if (filter != null && filter.equalsIgnoreCase(key)) {
                    Object val = item.get("value");
                    return val != null ? val.toString() : null;
                }
            } catch (Exception e) {
                log.error("Error while converting custom data: {}", e);
            }
        }
        return null;
    }
}
