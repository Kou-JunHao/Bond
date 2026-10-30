package com.bond.transfer.webdav;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebDAV 协议异常：直接携带 HTTP 状态码（含 207/507 等 WebDAV 语义）与响应头。
 *
 * <p>注意：{@code /dav/**} 的响应绝不能被 {@code GlobalExceptionHandler} 包成 JSON，
 * 所以异常在 {@link WebDavController} 内被捕获并原样写回。</p>
 */
public class WebDavException extends RuntimeException {

    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public WebDavException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public WebDavException header(String name, String value) {
        headers.put(name, value);
        return this;
    }
}
