package com.lmplatform.api.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClientResponseException;

/** Error descriptions are an allowlist, not excerpts from provider messages that may echo secrets or prompts. */
public record ApiFailure(String code, String message, Integer upstreamStatus, String detail) {
    public static ApiFailure from(Throwable exception) {
        if (exception instanceof InsufficientApiCreditException quota) {
            return new ApiFailure(quota.code(), quota.getMessage(), null, "阶段：API 额度预占；" + quota.getMessage());
        }
        Throwable cause = exception;
        StringBuilder clues = new StringBuilder();
        Integer status = null;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            clues.append(' ').append(cause.getClass().getSimpleName()).append(' ').append(cause.getMessage());
            if (cause instanceof RestClientResponseException response) {
                status = response.getStatusCode().value();
                clues.append(' ').append(response.getResponseBodyAsString());
            }
        }
        String text = clues.toString().toLowerCase(Locale.ROOT);
        var matcher = Pattern.compile("(?:upstream|ai service) returned (\\d{3})").matcher(text);
        if (matcher.find()) status = Integer.valueOf(matcher.group(1));
        String code = exception instanceof ApiGatewayException gateway ? gateway.code() : "upstream_error";
        if (!(exception instanceof ApiGatewayException)) {
            if (text.contains("client_disconnected")) code = "client_disconnected";
            else if (text.contains("timeout") || text.contains("timed out")) code = "upstream_timeout";
            else if (Integer.valueOf(401).equals(status) || Integer.valueOf(403).equals(status) || text.contains("invalid_api_key") || text.contains("configuration_error")) code = "upstream_auth_failed";
            else if (Integer.valueOf(429).equals(status) || text.contains("model_busy")) code = "model_busy";
            else if (text.contains("connection refused") || text.contains("connection reset") || text.contains("connectexception") || text.contains("unknownhost")) code = "upstream_unreachable";
            else if (Integer.valueOf(404).equals(status)) code = "upstream_model_not_found";
            else if (text.contains("context_length_exceeded") || text.contains("maximum context length") || text.contains("max_model_len")) code = "context_length_exceeded";
            else if (Integer.valueOf(400).equals(status) || Integer.valueOf(422).equals(status)) code = "upstream_invalid_request";
        }
        return of(code, status, exception.getClass().getSimpleName());
    }

    public static ApiFailure of(String code, Integer status, String stage) {
        String message = switch (code) {
            case "invalid_api_key" -> "API Key 无效、已停用或缺失，请检查调用配置";
            case "quota_exceeded" -> "可用额度不足，请申请额度或联系管理员";
            case "model_not_allowed" -> "该模型尚未开放 API 调用，请联系管理员";
            case "invalid_request" -> "请求参数不符合接口要求，请检查接口文档";
            case "upstream_timeout" -> "模型服务响应超时，请稍后重试";
            case "upstream_auth_failed" -> "模型服务认证失败，请联系管理员检查配置";
            case "upstream_model_not_found" -> "模型服务未找到指定模型，请联系管理员";
            case "context_length_exceeded" -> "请求超出模型上下文长度，请减少输入或输出上限";
            case "upstream_invalid_request" -> "模型服务拒绝了请求参数，请检查参数或联系管理员";
            case "upstream_unreachable" -> "无法连接模型服务，请稍后重试或联系管理员";
            case "model_busy" -> "模型服务繁忙，请稍后重试";
            case "client_disconnected" -> "调用端已断开连接，响应未完整接收";
            case "not_found" -> "接口不存在，请检查请求路径";
            default -> "模型调用失败，请凭请求 ID 联系管理员排查";
        };
        return new ApiFailure(code, message, status,
                "阶段：" + stage + (status == null ? "" : "；上游 HTTP 状态：" + status));
    }
}
