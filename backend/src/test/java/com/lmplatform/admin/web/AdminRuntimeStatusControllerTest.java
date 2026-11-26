package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.file.config.ObjectStorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminRuntimeStatusControllerTest {
    @Test
    void failedDependencyDoesNotHideOtherResultsOrExposeConnectionErrors() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("secret-connection-string"));
        @SuppressWarnings("unchecked") var redis = (ObjectProvider<RedisConnectionFactory>) mock(ObjectProvider.class);
        var controller = new AdminRuntimeStatusController(jdbc, redis, mock(MinioClient.class),
                new ObjectStorageProperties("", "", "", "files", 1024), new AiServiceProperties("invalid-url", 3, "", 40));
        try {
            var snapshot = controller.status();
            assertThat(snapshot.components()).hasSize(5);
            assertThat(snapshot.components().get(0).status()).isEqualTo("UP");
            assertThat(snapshot.components().get(2).status()).isEqualTo("DOWN");
            assertThat(snapshot.components().get(3).status()).isEqualTo("NOT_CONFIGURED");
            assertThat(snapshot.toString()).doesNotContain("secret-connection-string");
        } finally { controller.stop(); }
    }
}
