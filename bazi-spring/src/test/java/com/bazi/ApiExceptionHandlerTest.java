package com.bazi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the stable JSON error contract. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedExceptionsBecomeGeneric500() {
        ResponseEntity<Map<String, Object>> r = handler.handle(new IllegalStateException("secret detail"));

        assertEquals(500, r.getStatusCode().value());
        assertEquals(Map.of("error", "internal error"), r.getBody());
        assertFalse(r.getBody().containsValue("secret detail"));
    }

    @Test
    void springErrorResponsesKeepTheirStatus() {
        ResponseEntity<Map<String, Object>> r = handler.handle(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request"));

        assertEquals(400, r.getStatusCode().value());
        assertEquals("bad request", r.getBody().get("error"));
    }
}
