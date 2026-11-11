package com.lmplatform.api.web;

import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.api.service.ApiCallTrace;
import com.lmplatform.api.service.ApiFailure;
import java.util.LinkedHashMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {OpenAiProxyController.class, AdminApiController.class,
        DeveloperActivityController.class, AdminApiActivityController.class})
public class ApiGatewayExceptionHandler {

    @ExceptionHandler(ApiGatewayException.class)
    public ResponseEntity<Map<String, Map<String, String>>> handle(ApiGatewayException exception) {
        ApiCallTrace.failed(exception);
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Map<String, String>>> upstream(RestClientException exception) {
        ApiCallTrace.failed(exception);
        ApiFailure failure = ApiFailure.from(exception);
        int status = exception instanceof RestClientResponseException upstream ? upstream.getStatusCode().value() : 502;
        if (status == 401 || status == 403) status = 502;
        return response(failure.code().equals("upstream_timeout") ? 504 : status, failure.code(), failure.message());
    }

    private ResponseEntity<Map<String, Map<String, String>>> response(int status, String code, String message) {
        var error = new LinkedHashMap<String, String>();
        error.put("message", message); error.put("type", code); error.put("code", code);
        if (ApiCallTrace.current() != null) error.put("request_id", ApiCallTrace.current().requestId.toString());
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
