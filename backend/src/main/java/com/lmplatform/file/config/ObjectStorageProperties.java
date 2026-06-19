package com.lmplatform.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO endpoint, credentials, bucket, and upload safety limits. */
@ConfigurationProperties(prefix = "platform.object-storage")
public record ObjectStorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        long maxFileBytes
) {
}
