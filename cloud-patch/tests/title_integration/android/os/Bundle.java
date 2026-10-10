package android.os;

import java.util.HashMap;
import java.util.Map;

public final class Bundle {
    private final Map<String, Object> values = new HashMap<String, Object>();

    public Object get(String key) { return values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void putLong(String key, long value) { values.put(key, value); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public String getString(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }
    public long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? ((Long) value).longValue() : fallback;
    }
}
