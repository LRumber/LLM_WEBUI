package com.lmplatform.file.service;

import com.lmplatform.file.config.ObjectStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
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

    /** Reads one private object with a strict bound before embedding it into a model request. */
    public byte[] get(String objectKey, int maxBytes) throws Exception {
        try (var input = client.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket()).object(objectKey).build())) {
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) {
                throw new IllegalArgumentException("Image exceeds multimodal request limit");
            }
            return content;
        }
    }
}
