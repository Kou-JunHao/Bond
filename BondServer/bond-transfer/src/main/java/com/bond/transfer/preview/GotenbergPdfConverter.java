package com.bond.transfer.preview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Gotenberg 8 转换器实现（task-59）：{@code POST {endpoint}/forms/libreoffice/convert}，
 * multipart 字段名 {@code files}（Lead 已在生产实测：docx/xlsx/pptx 均返回 {@code %PDF-}）。
 *
 * <p>为避免把大文件整体读入内存，multipart 请求体先落临时文件，再用 {@code BodyPublishers.ofFile}
 * 流式发送（源文件最大 100MB，临时文件在请求完成后立即删除）。</p>
 *
 * <p>错误分类：
 * 连接失败/超时/无转换失败语义的 5xx → {@link ConversionUnavailableException}（对外 503，不缓存）；
 * 4xx 与「5xx + 转换失败语义」（如 {@code failed to convert} / {@code uno exception}）
 * → {@link BadDocumentException}（对外 415，可进负缓存）。</p>
 */
@Slf4j
@Component
public class GotenbergPdfConverter implements OfficePdfConverter {

    private static final String CONVERT_PATH = "/forms/libreoffice/convert";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int ERROR_DETAIL_LIMIT = 500;

    /**
     * Gotenberg/LibreOffice 在「文档本身打不开/结构残缺」时返回 5xx（生产实测：残缺 docx 返回
     * 500 {@code LibreOffice failed to convert the document ... usually a resource issue}）。
     * 这类失败语义是**文档问题**（对外 415 + 负缓存），不是转换服务故障（503）。
     */
    private static final java.util.List<String> CONVERSION_FAILURE_MARKERS = java.util.List.of(
            "failed to convert", "unable to convert", "could not convert", "cannot convert",
            "conversion failed", "libreoffice failed", "uno exception", "unsupported document",
            "source file could not be loaded", "generic input/output error");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public InputStream convert(String endpoint, Duration timeout, String fileName, InputStream source, long sourceSize,
                               java.util.Map<String, String> formFields) {
        Path bodyFile = null;
        try {
            MultipartBody body = buildMultipart(fileName, source, formFields);
            bodyFile = body.file();
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(endpoint) + CONVERT_PATH))
                    .timeout(timeout)
                    .header("Content-Type", body.contentType())
                    .POST(HttpRequest.BodyPublishers.ofFile(bodyFile))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response.body();
            }
            String detail = readDetail(response.body());
            if (status >= 500) {
                if (isConversionFailure(detail)) {
                    // 5xx 且响应体是"转换失败"语义：文档问题，对外 415（可进负缓存），不要误导用户重试
                    throw new BadDocumentException("gotenberg status " + status + ": " + detail);
                }
                throw new ConversionUnavailableException("gotenberg status " + status + ": " + detail);
            }
            throw new BadDocumentException("gotenberg rejected document: status " + status + ": " + detail);
        } catch (ConversionUnavailableException | BadDocumentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionUnavailableException("gotenberg request interrupted", e);
        } catch (IOException | RuntimeException e) {
            // 连接拒绝/DNS/超时（HttpTimeoutException 属 IOException）都归为服务不可用
            throw new ConversionUnavailableException("gotenberg request failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            if (bodyFile != null) {
                try {
                    Files.deleteIfExists(bodyFile);
                } catch (IOException e) {
                    log.debug("failed to delete gotenberg multipart temp file: {}", bodyFile, e);
                }
            }
        }
    }

    /**
     * 生成 multipart/form-data 请求体临时文件。
     *
     * <p>固定字段 {@code files}（本场景一次只转一个文件）；{@code formFields} 为可选的 Gotenberg
     * 表单参数（如表格的 {@code singlePageSheets=true}）。键与值都做白名单清洗，避免头注入。</p>
     */
    private MultipartBody buildMultipart(String fileName, InputStream source,
                                         java.util.Map<String, String> formFields) throws IOException {
        String boundary = "----BondOffice" + UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFileName(fileName);
        StringBuilder headBuilder = new StringBuilder();
        if (formFields != null) {
            for (java.util.Map.Entry<String, String> entry : formFields.entrySet()) {
                String name = sanitizeFieldName(entry.getKey());
                if (name.isEmpty()) {
                    continue;
                }
                headBuilder.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                        .append(sanitizeFieldValue(entry.getValue())).append("\r\n");
            }
        }
        headBuilder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"files\"; filename=\"").append(safeName).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        byte[] head = headBuilder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        Path file = Files.createTempFile("bond-gotenberg-", ".multipart");
        try (InputStream in = new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(head), source),
                new ByteArrayInputStream(tail))) {
            Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return new MultipartBody(file, "multipart/form-data; boundary=" + boundary);
    }

    /** multipart 字段名只允许字母/数字/连字符（Gotenberg 的表单参数都是这种形态）。 */
    static String sanitizeFieldName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    /** 表单值去掉换行（防头注入）并限长。 */
    static String sanitizeFieldValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", "").replace("\n", "").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    /**
     * 判断 Gotenberg 5xx 响应体是否是「文档转换失败」语义（而不是服务故障）。
     * 集中在一处，方便按生产日志补关键字与单测。
     */
    static boolean isConversionFailure(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        for (String marker : CONVERSION_FAILURE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 文件名只保留 basename，去掉引号/换行，防止 multipart 头注入。 */
    static String sanitizeFileName(String fileName) {        String name = fileName == null ? "document" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("\"", "").replace("\r", "").replace("\n", "").trim();
        if (name.isEmpty() || name.startsWith(".")) {
            name = "document" + name;
        }
        return name;
    }

    private static String trimTrailingSlash(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? "http://bond-gotenberg:3000" : value;
    }

    private static String readDetail(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(ERROR_DETAIL_LIMIT);
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        } catch (IOException e) {
            return "";
        }
    }

    private record MultipartBody(Path file, String contentType) {
    }
}
