package com.lmplatform.admin.web;

import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.file.config.ObjectStorageProperties;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.HttpURLConnection;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/runtime-status")
public class AdminRuntimeStatusController {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<RedisConnectionFactory> redis;
    private final MinioClient minio;
    private final ObjectStorageProperties storage;
    private final AiServiceProperties ai;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AdminRuntimeStatusController(JdbcTemplate jdbc, ObjectProvider<RedisConnectionFactory> redis,
            MinioClient minio, ObjectStorageProperties storage, AiServiceProperties ai) {
        this.jdbc = jdbc; this.redis = redis; this.minio = minio; this.storage = storage; this.ai = ai;
    }

    public record Component(String name, String status) {}
    public record Snapshot(String version, Instant checkedAt, List<Component> components) {}

    @GetMapping
    public synchronized Snapshot status() throws InterruptedException {
        List<String> names = List.of("核心服务", "AI 服务", "数据库", "Redis", "MinIO 存储桶");
        List<Callable<String>> checks = List.of(() -> "UP", () -> {
            var connection = (HttpURLConnection) URI.create(ai.baseUrl().replaceAll("/$", "") + "/health").toURL().openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000); connection.setInstanceFollowRedirects(false);
            try { return connection.getResponseCode() == 200 ? "UP" : "DOWN"; }
            finally { connection.disconnect(); }
        }, () -> Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)) ? "UP" : "DOWN", () -> {
            var factory = redis.getIfAvailable();
            if (factory == null) return "NOT_CONFIGURED";
            try (var connection = factory.getConnection()) { return "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN"; }
        }, () -> minio.bucketExists(BucketExistsArgs.builder().bucket(storage.bucket()).build()) ? "UP" : "DOWN");
        var results = executor.invokeAll(checks, 3, TimeUnit.SECONDS);
        List<Component> components = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String state;
            try { state = results.get(i).get(); }
            catch (CancellationException ex) { state = "TIMEOUT"; }
            catch (ExecutionException ex) { state = "DOWN"; }
            components.add(new Component(names.get(i), state));
        }
        String version = getClass().getPackage().getImplementationVersion();
        return new Snapshot(version == null ? "开发版本" : version, Instant.now(), components);
    }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }
}
