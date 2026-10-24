package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DataStore {
    public final JsonNode data;          // bazi-data.json
    public final JsonNode solar;         // solar-data.json

    public DataStore() {
        ObjectMapper om = new ObjectMapper();
        try (InputStream a = getClass().getResourceAsStream("/data/bazi-data.json");
             InputStream b = getClass().getResourceAsStream("/data/solar-data.json")) {
            data = om.readTree(a);
            solar = om.readTree(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode node(String... path) {
        JsonNode n = data;
        for (String p : path) n = n.get(p);
        return n;
    }

    /** Deep-convert a JsonNode into plain Java values (Map/List/String/Long/Double/Boolean/null)
     *  so charts stay JSON-type faithful (numbers remain numbers, null stays null). */
    public static Object deep(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isFloatingPointNumber()) return n.asDouble();
        if (n.isArray()) {
            List<Object> out = new ArrayList<>(n.size());
            n.forEach(x -> out.add(deep(x)));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> out.put(e.getKey(), deep(e.getValue())));
        return out;
    }

    /** Deep-convert an object node; missing/null nodes yield an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMap(JsonNode n) {
        Object o = deep(n);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /** Deep-convert an array node; missing/null nodes yield an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> deepList(JsonNode n) {
        Object o = deep(n);
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }
}
