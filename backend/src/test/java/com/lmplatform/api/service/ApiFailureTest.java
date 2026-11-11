package com.lmplatform.api.service;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

class ApiFailureTest {
    @Test
    void categorizesTimeoutAndModelErrorsWithoutPersistingProviderContent() {
        var timeout = ApiFailure.from(new SocketTimeoutException("private key and prompt"));
        assertThat(timeout.code()).isEqualTo("upstream_timeout");
        assertThat(timeout.message() + timeout.detail()).doesNotContain("private key", "prompt");
        var auth = ApiFailure.from(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "secret", null,
                "private provider response".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        assertThat(auth.code()).isEqualTo("upstream_auth_failed");
        assertThat(auth.upstreamStatus()).isEqualTo(401);
        assertThat(auth.detail()).doesNotContain("private", "secret");
        var busy = ApiFailure.from(new IOException("AI service returned 429: private response"));
        assertThat(busy.code()).isEqualTo("model_busy");
        assertThat(busy.upstreamStatus()).isEqualTo(429);
        assertThat(ApiFailure.from(new IOException("context_length_exceeded private prompt")).code())
                .isEqualTo("context_length_exceeded");
        assertThat(ApiFailure.from(new IOException("client_disconnected")).code()).isEqualTo("client_disconnected");
    }
}
