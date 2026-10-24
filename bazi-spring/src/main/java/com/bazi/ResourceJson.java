package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily loads JSON tables from {@code /data}. Complements {@link DataStore},
 * which eagerly loads the Bazi + solar files; the per-system files (starting
 * with {@code almanac-data.json}) stay out of the hot path until first use.
 */
@Component
public class ResourceJson {

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public JsonNode load(String name) {
        return cache.computeIfAbsent(name, n -> {
            try (InputStream in = getClass().getResourceAsStream("/data/" + n)) {
                if (in == null) throw new IllegalStateException("missing data resource: /data/" + n);
                return om.readTree(in);
            } catch (IOException e) {
                throw new IllegalStateException("failed to read /data/" + n, e);
            }
        });
    }
}
