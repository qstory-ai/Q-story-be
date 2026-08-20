package com.qstory.backend.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class EdgeApiExceptionHandler {

    @ExceptionHandler(EdgeException.class)
    public ResponseEntity<Map<String, Object>> handle(EdgeException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error.code().wireCode());
        if (error.stage() != null) {
            body.put("stage", error.stage());
        }
        return ResponseEntity.status(HttpStatus.valueOf(error.statusCode())).body(body);
    }
}
