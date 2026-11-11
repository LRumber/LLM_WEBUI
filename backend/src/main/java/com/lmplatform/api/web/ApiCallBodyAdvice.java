package com.lmplatform.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lmplatform.api.service.ApiCallTrace;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes = OpenAiProxyController.class)
public class ApiCallBodyAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type target,
            Class<? extends HttpMessageConverter<?>> converter) {
        if (body instanceof JsonNode json) ApiCallTrace.request(json.path("model").asText(""), json.path("stream").asBoolean(false));
        return body;
    }
}
