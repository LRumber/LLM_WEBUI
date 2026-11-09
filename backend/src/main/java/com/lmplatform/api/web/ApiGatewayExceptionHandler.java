package com.lmplatform.api.web;

import com.lmplatform.api.service.ApiGatewayException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {OpenAiProxyController.class, AdminApiController.class})
public class ApiGatewayExceptionHandler {

    @ExceptionHandler(ApiGatewayException.class)
    public ResponseEntity<Map<String, Map<String, String>>> handle(ApiGatewayException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "error", Map.of(
                        "message", exception.getMessage(),
                        "type", exception.code(),
                        "code", exception.code()
                )
        ));
    }
}
