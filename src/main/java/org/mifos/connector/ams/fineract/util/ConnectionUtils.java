package org.mifos.connector.ams.fineract.util;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.util.json.JsonObject;
import org.json.JSONArray;
import org.json.JSONObject;

/** Contains utility methods for connection. */
@Slf4j
public class ConnectionUtils {

    /** Longest response body written to a log line. */
    public static final int MAX_LOGGED_BODY_LENGTH = 1000;

    private ConnectionUtils() {}

    /**
     * Shortens a response body for a log line: an nginx error page or a large payload would otherwise flood the logs.
     *
     * @param body
     *            the body, may be null
     * @return the body, cut to {@link #MAX_LOGGED_BODY_LENGTH} characters with a marker saying how long it was
     */
    public static String truncate(String body) {
        if (body == null || body.length() <= MAX_LOGGED_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_LOGGED_BODY_LENGTH) + "...[truncated, " + body.length() + " chars]";
    }

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
