package com.lmplatform.file.service;

import com.lmplatform.file.config.ObjectStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Service;

/** Writes private binary objects to MinIO without exposing presigned credentials to browsers. */
@Service
public class ObjectStorageService {

    private final MinioClient client;
    private final ObjectStorageProperties properties;

    public ObjectStorageService(MinioClient client, ObjectStorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void put(String objectKey, byte[] content, String mediaType) throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
        }
        client.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .contentType(mediaType)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .build());
    }
}
