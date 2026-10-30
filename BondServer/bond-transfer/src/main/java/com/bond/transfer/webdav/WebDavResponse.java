package com.bond.transfer.webdav;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebDAV 原始响应（状态码 + 响应头 + XML/字节体或流式体）。
 *
 * <p>只由 {@link WebDavController} 消费并直接写入 {@code HttpServletResponse}，
 * 因此不会被 Spring 的统一 JSON 响应包装改写。</p>
 */
public final class WebDavResponse {

    public static final String CONTENT_TYPE_XML = "application/xml; charset=\"utf-8\"";

    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body;
    private InputStream stream;
    private long contentLength = -1;

    private WebDavResponse(int status) {
        this.status = status;
    }

    public static WebDavResponse status(int status) {
        return new WebDavResponse(status);
    }

    public static WebDavResponse xml(int status, String xml) {
        WebDavResponse response = new WebDavResponse(status);
        response.body = xml.getBytes(StandardCharsets.UTF_8);
        response.headers.put("Content-Type", CONTENT_TYPE_XML);
        response.headers.put("Content-Length", String.valueOf(response.body.length));
        return response;
    }

    /**
     * 纯文本错误体（task-47）：5xx 不允许"空体 + 零日志"，至少返回可读文本，
     * 方便客户端与代理链路定位问题。
     */
    public static WebDavResponse text(int status, String text) {
        WebDavResponse response = new WebDavResponse(status);
        response.body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        response.headers.put("Content-Type", "text/plain; charset=utf-8");
        response.headers.put("Content-Length", String.valueOf(response.body.length));
        return response;
    }

    public static WebDavResponse stream(int status, InputStream stream, long contentLength) {
        WebDavResponse response = new WebDavResponse(status);
        response.stream = stream;
        response.contentLength = contentLength;
        return response;
    }

    public WebDavResponse header(String name, String value) {
        if (value != null) {
            headers.put(name, value);
        }
        return response();
    }

    public WebDavResponse headers(Map<String, String> values) {
        if (values != null) {
            headers.putAll(values);
        }
        return response();
    }

    private WebDavResponse response() {
        return this;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public InputStream getStream() {
        return stream;
    }

    public long getContentLength() {
        return contentLength;
    }
}
