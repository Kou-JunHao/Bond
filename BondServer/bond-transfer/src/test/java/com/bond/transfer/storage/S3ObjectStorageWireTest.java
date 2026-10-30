package com.bond.transfer.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS SDK v2 真实请求构造测试（不连网络/不需要 Docker）：
 * 用伪 {@link SdkHttpClient} 捕获 SDK 发出的 HTTP 请求并返回构造好的 S3 XML/响应，
 * 覆盖 task-8 中 Lead 未能验证的三项：&gt;5MB 分片上传、range 下载、预签名 URL。
 */
class S3ObjectStorageWireTest {

    private static final String BUCKET = "bond-transfer";
    private static final long MB5 = 5L * 1024 * 1024;

    private final RecordingHttpClient http = new RecordingHttpClient();
    private final StorageSettings settings = new StorageSettings(
            "http://localhost:9000", "test-ak", "test-sk", BUCKET, "us-east-1", true, 3600);

    private S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(true)
                .httpClient(http)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    private S3Presigner presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Test
    @DisplayName(">5MB 分片上传：init → uploadPart(5MB) → uploadPart(1MB) → complete 全链路")
    void multipartUploadOver5Mb() {
        byte[] part1 = patternBytes((int) MB5, (byte) 0x11);
        byte[] part2 = patternBytes(1024 * 1024, (byte) 0x22);

        try (S3Client s3 = client(); S3Presigner presigner = presigner()) {
            S3ObjectStorage storage = new S3ObjectStorage(s3, presigner, settings);

            String uploadId = storage.initMultipart("transfers/1/big.bin", "application/octet-stream");
            assertEquals("upload-wire-1", uploadId);

            String etag1 = storage.uploadPart("transfers/1/big.bin", uploadId, 1,
                    new ByteArrayInputStream(part1), part1.length);
            String etag2 = storage.uploadPart("transfers/1/big.bin", uploadId, 2,
                    new ByteArrayInputStream(part2), part2.length);
            assertNotNull(etag1);
            assertNotNull(etag2);

            storage.completeMultipart("transfers/1/big.bin", uploadId, List.of(
                    new ObjectStorage.PartETag(1, etag1, part1.length),
                    new ObjectStorage.PartETag(2, etag2, part2.length)));
        }

        List<Captured> calls = http.calls();
        List<Captured> putCalls = calls.stream().filter(c -> c.method().equals("PUT")).toList();
        assertEquals(2, putCalls.size(), "expected two uploadPart PUT requests");
        // 真实 body 字节数校验（证明 >5MB 分片确实按原样上传，未被截断/加 trailer）
        assertEquals(part1.length, putCalls.get(0).body().length, "first part should be 5MB");
        assertEquals(part2.length, putCalls.get(1).body().length, "second part should be 1MB");
        assertArrayEquals(firstBytes(part1), firstBytes(putCalls.get(0).body()));
        assertTrue(putCalls.get(0).query().contains("partNumber=1"), "partNumber query expected");
        assertTrue(putCalls.get(0).query().contains("uploadId=upload-wire-1"));
        // complete 请求确实带上了 uploadId
        Captured complete = calls.stream()
                .filter(c -> c.method().equals("POST") && c.query().contains("uploadId"))
                .findFirst().orElseThrow();
        String completeBody = new String(complete.body(), StandardCharsets.UTF_8);
        assertTrue(completeBody.contains("PartNumber") || completeBody.contains("partNumber"));
    }

    @Test
    @DisplayName("Range 下载：SDK 发送 Range: bytes=100-109，返回 206 的 10 字节")
    void rangeDownloadSendsRangeHeader() throws Exception {
        byte[] slice = patternBytes(10, (byte) 0x33);
        http.nextGetResponse(slice, 206);

        try (S3Client s3 = client(); S3Presigner presigner = presigner()) {
            S3ObjectStorage storage = new S3ObjectStorage(s3, presigner, settings);
            try (InputStream in = storage.getObject("workspaces/1/demo.bin", 100, 10)) {
                assertArrayEquals(slice, in.readAllBytes());
            }
        }

        Captured get = http.calls().stream()
                .filter(c -> c.method().equals("GET"))
                .findFirst().orElseThrow();
        assertEquals("bytes=100-109", get.headers().getOrDefault("Range", ""));
        assertTrue(get.path().contains("/" + BUCKET + "/workspaces/1/demo.bin"), "path-style URL expected: " + get.path());
    }

    @Test
    @DisplayName("预签名 URL：path-style + X-Amz-Signature + X-Amz-Expires，且 bucket/key 路径正确")
    void presignedGetUrlShape() {
        try (S3Client s3 = client(); S3Presigner presigner = presigner()) {
            S3ObjectStorage storage = new S3ObjectStorage(s3, presigner, settings);
            String url = storage.presignedGetUrl("transfers/9/report.pdf", Duration.ofSeconds(60));

            URI uri = URI.create(url);
            assertTrue(url.contains("X-Amz-Signature="), "signature query expected: " + url);
            assertTrue(url.contains("X-Amz-Expires=60"), "expiry query expected: " + url);
            assertTrue(uri.getPath().startsWith("/" + BUCKET + "/"), "path-style bucket path expected: " + uri.getPath());
            assertTrue(uri.getPath().endsWith("/transfers/9/report.pdf"));
            assertFalse(url.contains("test-sk"), "secret key must never leak into presigned URL");
        }
    }

    private static byte[] patternBytes(int size, byte seed) {
        byte[] data = new byte[size];
        Arrays.fill(data, seed);
        return data;
    }

    private static byte[] firstBytes(byte[] data) {
        return Arrays.copyOf(data, Math.min(1024, data.length));
    }

    // ── 伪 S3 HTTP 客户端 ──

    private record Captured(String method, String path, String query, byte[] body,
                            java.util.Map<String, String> headers) {
    }

    /** 捕获请求 + 返回构造响应；支持 init/uploadPart/complete/GET(range) 的最小 S3 XML。 */
    private static class RecordingHttpClient implements SdkHttpClient {
        private final List<Captured> captured = new ArrayList<>();
        private byte[] getBody = new byte[0];
        private int getStatus = 200;

        void nextGetResponse(byte[] body, int status) {
            this.getBody = body;
            this.getStatus = status;
        }

        List<Captured> calls() {
            return captured;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            SdkHttpRequest httpRequest = request.httpRequest();
            byte[] body = readBody(request);
            String method = httpRequest.method().name();
            String path = httpRequest.encodedPath();
            String query = httpRequest.encodedQueryParameters().orElse("");
            captured.add(new Captured(method, path, query, body, httpRequest.headers().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey(),
                            e -> e.getValue().isEmpty() ? "" : e.getValue().get(0)))));

            final int status;
            final byte[] responseBody;
            final List<String> headerNames = new ArrayList<>();
            final List<String> headerValues = new ArrayList<>();
            if ("PUT".equals(method)) {
                status = 200;
                responseBody = new byte[0];
                headerNames.add("ETag");
                headerValues.add("\"etag-wire\"");
            } else if ("POST".equals(method) && query.contains("uploads")) {
                status = 200;
                responseBody = initXml().getBytes(StandardCharsets.UTF_8);
                headerNames.add("Content-Type");
                headerValues.add("application/xml");
            } else if ("POST".equals(method)) {
                status = 200;
                responseBody = completeXml().getBytes(StandardCharsets.UTF_8);
                headerNames.add("Content-Type");
                headerValues.add("application/xml");
            } else {
                status = getStatus;
                responseBody = getBody;
                if (status == 206) {
                    headerNames.add("Content-Range");
                    headerValues.add("bytes 100-109/2048");
                }
            }

            SdkHttpResponse.Builder responseBuilder = SdkHttpResponse.builder()
                    .statusCode(status)
                    .putHeader("Content-Length", String.valueOf(responseBody.length));
            for (int i = 0; i < headerNames.size(); i++) {
                responseBuilder.putHeader(headerNames.get(i), headerValues.get(i));
            }
            HttpExecuteResponse executeResponse = HttpExecuteResponse.builder()
                    .response(responseBuilder.build())
                    .responseBody(AbortableInputStream.create(new ByteArrayInputStream(responseBody)))
                    .build();
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    return executeResponse;
                }

                @Override
                public void abort() {
                }
            };
        }

        private static byte[] readBody(HttpExecuteRequest request) {
            Optional<software.amazon.awssdk.http.ContentStreamProvider> provider = request.contentStreamProvider();
            if (provider.isEmpty()) {
                return new byte[0];
            }
            byte[] raw;
            try (InputStream in = provider.get().newStream()) {
                raw = in.readAllBytes();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            // SDK 对带 body 的 S3 请求会使用 aws-chunked（每块带 chunk-signature，尾部带校验和）。
            // 真实 S3/RustFS 会解码；这里对捕获到的请求做同样解码，以便断言逻辑字节数。
            boolean awsChunked = request.httpRequest().firstMatchingHeader("x-amz-decoded-content-length").isPresent()
                    || request.httpRequest().firstMatchingHeader("Content-Encoding").stream()
                    .anyMatch(v -> v.contains("aws-chunked"));
            return awsChunked ? decodeAwsChunked(raw) : raw;
        }

        private static byte[] decodeAwsChunked(byte[] raw) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int pos = 0;
            while (pos < raw.length) {
                int lineEnd = -1;
                for (int i = pos; i + 1 < raw.length; i++) {
                    if (raw[i] == '\r' && raw[i + 1] == '\n') {
                        lineEnd = i;
                        break;
                    }
                }
                if (lineEnd < 0) {
                    break;
                }
                String line = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII);
                int semicolon = line.indexOf(';');
                String hex = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
                int size;
                try {
                    size = Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    break;
                }
                pos = lineEnd + 2;
                if (size == 0) {
                    break;
                }
                if (pos + size > raw.length) {
                    break;
                }
                out.write(raw, pos, size);
                pos += size;
                if (pos + 1 < raw.length && raw[pos] == '\r' && raw[pos + 1] == '\n') {
                    pos += 2;
                }
            }
            return out.toByteArray();
        }

        private static String initXml() {
            return "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Bucket>" + BUCKET + "</Bucket><Key>transfers/1/big.bin</Key>"
                    + "<UploadId>upload-wire-1</UploadId></InitiateMultipartUploadResult>";
        }

        private static String completeXml() {
            return "<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Location>http://localhost:9000/" + BUCKET + "/transfers/1/big.bin</Location>"
                    + "<Bucket>" + BUCKET + "</Bucket><Key>transfers/1/big.bin</Key>"
                    + "<ETag>\"etag-wire\"</ETag></CompleteMultipartUploadResult>";
        }

        @Override
        public void close() {
        }

        @Override
        public String clientName() {
            return "recording-http-client";
        }
    }
}
