package cn.sunnystring.bletalkie.core;
import org.json.*;

public final class Json {
    private Json() {}
    public static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("JSON key/value pairs");
        try { for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); }
        catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
        return result;
    }
}
