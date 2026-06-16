package com.bond.transfer.service;

import io.minio.*;
import io.minio.messages.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;

@Service
public class MinioService {

    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public MinioService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to initialize Minio bucket", e);
        }
    }

    public String initMultipartUpload(String objectPath) throws Exception {
        return minioClient.createMultipartUpload(
                CreateMultipartUploadArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .build()).result().uploadId();
    }

    public String uploadPart(String objectPath, String uploadId, int partNumber, InputStream data, long size) throws Exception {
        return minioClient.uploadPart(
                UploadPartArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .stream(data, size, -1)
                        .build()).etag();
    }

    public void completeMultipartUpload(String objectPath, String uploadId, Part[] parts) throws Exception {
        minioClient.completeMultipartUpload(
                CompleteMultipartUploadArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .uploadId(uploadId)
                        .parts(parts)
                        .build());
    }

    public void abortMultipartUpload(String objectPath, String uploadId) throws Exception {
        minioClient.abortMultipartUpload(
                AbortMultipartUploadArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .uploadId(uploadId)
                        .build());
    }

    public InputStream downloadObject(String objectPath) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .build());
    }

    public void uploadObject(String objectPath, InputStream data, long size, String contentType) throws Exception {
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .stream(data, size, -1)
                        .contentType(contentType)
                        .build());
    }

    public void deleteObject(String objectPath) throws Exception {
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .build());
    }

    public List<Part> listParts(String objectPath, String uploadId) throws Exception {
        return minioClient.listParts(
                ListPartsArgs.builder()
                        .bucket(bucket)
                        .object(objectPath)
                        .uploadId(uploadId)
                        .build()).result().partList();
    }
}
