package com.bond.transfer.document;

import com.bond.common.exception.BusinessException;
import com.bond.transfer.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 在线文档导出 PDF 客户端（task-137）。
 *
 * <p>生产已有 Gotenberg（{@code bond-gotenberg:3000}，1panel-network 内网可达），
 * 这里直接以 Chromium HTML→PDF 路由复用，不引入新依赖、不新增服务：</p>
 *
 * <pre>POST {endpoint}/forms/chromium/convert/html  multipart/form-data( files=index.html )</pre>
 *
 * <p>配置（都有安全默认值）：</p>
 * <ul>
 *   <li>{@code document.export.gotenberg_endpoint} 默认 {@code http://bond-gotenberg:3000}</li>
 *   <li>{@code document.export.asset_base_url} 默认 {@code http://bond-transfer:8082}，
 *       用于把正文里相对路径的 {@code <img src="/api/...">} 补成容器内可抓取的绝对地址</li>
 * </ul>
 *
 * <p>失败语义：超时 30s；连接失败/非 2xx/返回非 PDF 一律 {@link BusinessException}(503)，
 * message 面向客户端可读，不泄露内部地址。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentPdfExporter {

    static final String CONFIG_ENDPOINT = "document.export.gotenberg_endpoint";
    static final String CONFIG_ASSET_BASE_URL = "document.export.asset_base_url";
    static final String DEFAULT_ENDPOINT = "http://bond-gotenberg:3000";
    static final String DEFAULT_ASSET_BASE_URL = "http://bond-transfer:8082";
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String UNAVAILABLE = "文档转换服务暂时不可用，请稍后重试";

    private final ConfigService configService;

    /** 把（已净化）HTML 转换为 PDF；失败抛 503。 */
    public byte[] toPdf(String html) {
        String endpoint = trimSlash(configService.get(CONFIG_ENDPOINT, DEFAULT_ENDPOINT));
        String assetBaseUrl = trimSlash(configService.get(CONFIG_ASSET_BASE_URL, DEFAULT_ASSET_BASE_URL));
        String normalizedHtml = absolutizeAssetUrls(html == null ? "" : html, assetBaseUrl);
        MultipartBody multipart = buildMultipart(normalizedHtml);
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/forms/chromium/convert/html"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] pdf = response.body();
            if (response.statusCode() / 100 != 2 || pdf == null || pdf.length < 4
                    || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
                log.warn("document pdf export failed: status={}, bytes={}",
                        response.statusCode(), pdf == null ? 0 : pdf.length);
                throw new BusinessException(503, UNAVAILABLE);
            }
            return pdf;
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("document pdf export interrupted: {}", e.getMessage());
            throw new BusinessException(503, UNAVAILABLE);
        } catch (Exception e) {
            log.warn("document pdf export failed: {}", e.getMessage());
            throw new BusinessException(503, UNAVAILABLE);
        }
    }

    /** 把相对 HTTP 路径（{@code /api/...}）补容器内基址，Gotenberg 才能抓到图片。 */
    static String absolutizeAssetUrls(String html, String assetBaseUrl) {
        if (html == null || html.isEmpty() || assetBaseUrl == null || assetBaseUrl.isBlank()) {
            return html == null ? "" : html;
        }
        String base = trimSlash(assetBaseUrl);
        return html.replaceAll("(src|href)=\"(/(?!/)[^\"]*)\"",
                "$1=\"" + java.util.regex.Matcher.quoteReplacement(base) + "$2\"");
    }

    static MultipartBody buildMultipart(String html) {
        String boundary = "----BondDocumentBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"index.html\"\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(html.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(tail.getBytes(StandardCharsets.UTF_8));
        return new MultipartBody(out.toByteArray(), boundary);
    }

    static String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    record MultipartBody(byte[] body, String boundary) {
    }
}
