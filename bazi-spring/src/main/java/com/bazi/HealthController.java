package com.bazi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight liveness/readiness probe for deployments.
 *
 * <p>Checks that the eagerly loaded Bazi/solar tables are present and that the
 * lazily loaded Qi Men and Library resources can be read (the first call also
 * warms them). Returns 503 when a dataset is missing so orchestrators do not
 * route traffic to a broken instance. No exception details are exposed.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataStore store;
    private final ResourceJson resources;

    public HealthController(DataStore store, ResourceJson resources) {
        this.store = store;
        this.resources = resources;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean bazi = store.data != null && store.solar != null;
        boolean qimen = present("qimen-data.json");
        boolean library = present("library-data.json");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bazi", bazi);
        data.put("qimen", qimen);
        data.put("library", library);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", bazi && qimen && library ? "ok" : "error");
        body.put("app", "bazi-spring");
        body.put("time", Instant.now().toString());
        body.put("data", data);

        return bazi && qimen && library
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(503).body(body);
    }

    private boolean present(String name) {
        try {
            return resources.load(name) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
