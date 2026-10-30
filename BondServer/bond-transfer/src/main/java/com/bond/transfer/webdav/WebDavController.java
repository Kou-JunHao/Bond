package com.bond.transfer.webdav;

import com.bond.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebDAV 统一入口（原始协议响应，不经过 JSON 包装）。
 *
 * <p>PROPFIND/MKCOL/MOVE/COPY/LOCK/UNLOCK 等自定义方法无法写成 Java 方法名，
 * 因此这里用一个 handler 接收 {@code /dav} 与 {@code /dav/**}，按 HTTP method 字符串分发。</p>
 *
 * <p>异常必须在本类内部消化：一旦抛到 {@code GlobalExceptionHandler}，
 * 响应会被包装成 JSON，Windows Explorer 会直接放弃挂载。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebDavController {

    private final WebDavService webDavService;

    @RequestMapping({"/dav", "/dav/**"})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebDavResponse result;
        try {
            result = webDavService.handle(request);
        } catch (WebDavException e) {
            // task-47：禁止"5xx + 空体 + 零日志"。5xx 记录完整堆栈并返回非空文本体，
            // 4xx/507 属于协议语义，保持原有空体 + 状态码。
            if (e.getStatus() >= 500) {
                log.error("WebDAV {} {} failed: status={} message={}",
                        request.getMethod(), request.getRequestURI(), e.getStatus(), e.getMessage(), e);
                result = WebDavResponse.text(e.getStatus(), "Internal Server Error")
                        .headers(e.getHeaders());
            } else if (e.getStatus() == 401) {
                // task-126：401 不能再是空体——给客户端/排障者一个可读文本，
                // 同时保留 WWW-Authenticate（Basic）。
                result = WebDavResponse.text(401, "Unauthorized").headers(e.getHeaders());
            } else {
                result = WebDavResponse.status(e.getStatus()).headers(e.getHeaders());
            }
        } catch (BusinessException e) {
            int status = e.getCode() >= 400 && e.getCode() <= 599 ? e.getCode() : 500;
            if (status >= 500) {
                log.error("WebDAV {} {} failed: business code={} message={}",
                        request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage(), e);
                result = WebDavResponse.text(status, "Internal Server Error");
            } else {
                result = WebDavResponse.status(status);
            }
        } catch (Exception e) {
            log.error("WebDAV {} {} failed unexpectedly", request.getMethod(), request.getRequestURI(), e);
            result = WebDavResponse.text(500, "Internal Server Error");
        }
        write(response, result);
    }

    private void write(HttpServletResponse response, WebDavResponse result) throws IOException {
        response.setStatus(result.getStatus());
        for (Map.Entry<String, String> header : result.getHeaders().entrySet()) {
            response.setHeader(header.getKey(), header.getValue());
        }
        if (result.getBody() != null) {
            if (!result.getHeaders().containsKey("Content-Length")) {
                response.setContentLength(result.getBody().length);
            }
            response.getOutputStream().write(result.getBody());
        } else if (result.getStream() != null) {
            try (InputStream in = result.getStream()) {
                in.transferTo(response.getOutputStream());
            }
        } else if (result.getContentLength() == 0) {
            response.setContentLength(0);
        }
        response.flushBuffer();
    }
}
