package com.bazi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable JSON error contract for unexpected failures.
 *
 * <p>Controller-local handlers (Bazi/QiMen/Library validation) still win. This
 * advice only sees what nobody else handled: Spring MVC exceptions keep their
 * status (404/405/415/...) via {@link ErrorResponse}, everything else becomes a
 * generic 500 so internal messages never reach clients.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception e) {
        if (e instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            String detail = er.getBody().getDetail();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", detail != null && !detail.isBlank()
                    ? detail : "request failed");
            log.debug("API {}: {}", status.value(), e.getMessage());
            return ResponseEntity.status(status).body(body);
        }

        log.error("Unhandled API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal error"));
    }
}
