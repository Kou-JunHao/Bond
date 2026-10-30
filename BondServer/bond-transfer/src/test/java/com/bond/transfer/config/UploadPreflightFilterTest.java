package com.bond.transfer.config;

import com.bond.common.security.GatewaySignature;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-26：Content-Length 预检——在 MultipartResolver 消费请求体之前拒绝超档位上传。
 */
class UploadPreflightFilterTest {

    private static final long USER_ID = 42L;

    private EntitlementResolver entitlementResolver;
    private ConfigService configService;
    private UploadPreflightFilter filter;

    @BeforeEach
    void setUp() {
        entitlementResolver = mock(EntitlementResolver.class);
        configService = mock(ConfigService.class);
        filter = new UploadPreflightFilter(entitlementResolver, configService);
        when(configService.getLong("transfer.max_chunk_size", UploadPreflightFilter.DEFAULT_MAX_CHUNK_SIZE))
                .thenReturn(UploadPreflightFilter.DEFAULT_MAX_CHUNK_SIZE);
    }

    private static Entitlements plan(long maxFileSize) {
        return new Entitlements("free", new PlanLimits(
                10_000_000_000L, 5_000_000_000L, maxFileSize,
                2, 1, 3, 0L, 7, 3, 30, 5, 30), null);
    }

    private static MockHttpServletRequest uploadRequest(String method, String path, long contentLength) {
        MockHttpServletRequest request;
        if (contentLength >= 0) {
            // MockHttpServletRequest 没有 content-length setter，按需覆写 getter 模拟客户端声明
            final long declaredLength = contentLength;
            request = new MockHttpServletRequest(method, path) {
                @Override
                public int getContentLength() {
                    return (int) declaredLength;
                }

                @Override
                public long getContentLengthLong() {
                    return declaredLength;
                }
            };
        } else {
            request = new MockHttpServletRequest(method, path);
        }
        request.addHeader(GatewaySignature.HEADER_USER_ID, String.valueOf(USER_ID));
        return request;
    }

    @Test
    @DisplayName("Content-Length 超过档位 → 413，且过滤链不继续（不会进入 MultipartResolver）")
    void rejectsOversizedUploadBeforeMultipartParsing() throws Exception {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(1000L));
        MockHttpServletRequest request = uploadRequest("POST", "/api/files/upload", 5000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":413"), body);
        assertTrue(body.contains("文件超出当前档位上限"), body);
        assertNull(chain.getRequest(), "413 后不应继续过滤链，请求体不能被 multipart 解析");
        verify(entitlementResolver).resolveFresh(USER_ID);
    }

    @Test
    @DisplayName("Content-Length 未超档位 → 放行")
    void passesWhenWithinPlanLimit() throws Exception {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(1000L));
        MockHttpServletRequest request = uploadRequest("POST", "/api/files/upload", 500L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("Content-Length 恰好等于档位上限 → 放行（严格大于才拒绝）")
    void passesWhenContentLengthEqualsLimit() throws Exception {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(1000L));
        MockHttpServletRequest request = uploadRequest("POST", "/api/files/upload", 1000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("缺少 Content-Length（chunked）→ 放行并只 warn 一次")
    void passesWhenContentLengthMissing() throws Exception {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(1000L));

        MockFilterChain first = new MockFilterChain();
        filter.doFilter(uploadRequest("POST", "/api/files/upload", -1), new MockHttpServletResponse(), first);
        assertNotNull(first.getRequest(), "chunked 请求应放行由业务层兜底");

        MockFilterChain second = new MockFilterChain();
        filter.doFilter(uploadRequest("POST", "/api/files/upload", -1), new MockHttpServletResponse(), second);
        assertNotNull(second.getRequest());
    }

    @Test
    @DisplayName("未登录（无 X-User-Id）→ 放行，交给后续签名校验；不解析档位")
    void passesWhenUserMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/files/upload") {
            @Override
            public long getContentLengthLong() {
                return 2_000_000_000L;
            }

            @Override
            public int getContentLength() {
                return 2_000_000_000;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        verifyNoInteractions(entitlementResolver);
    }

    @Test
    @DisplayName("非上传路径（如 content 下载）即使 Content-Length 很大也不受影响")
    void doesNotAffectNonUploadPaths() throws Exception {
        MockHttpServletRequest request = uploadRequest("GET", "/api/files/1/content", 2_000_000_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        verifyNoInteractions(entitlementResolver);
    }

    @Test
    @DisplayName("分片请求再受 transfer.max_chunk_size 约束（含 1MB multipart 边界余量）")
    void rejectsOversizedChunkBody() throws Exception {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(2_000_000_000L));
        // 64MB + 1MB 余量 = 65MB；100MB 明显超限
        MockHttpServletRequest request = uploadRequest("POST", "/api/files/chunks/upload-1/0", 100L * 1024 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("工作区分片路径同样受单分片上限保护，正常分片放行")
    void chunkBodyWithinLimitPasses() throws Exception {
        long withinLimit = 10L * 1024 * 1024;
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(2_000_000_000L));
        MockHttpServletRequest request = uploadRequest(
                "PUT", "/api/workspaces/7/files/chunks/upload-2/3", withinLimit);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("初始化/完成等 JSON 接口不受分片上限影响（只按档位判断）")
    void chunkInitJsonIsNotCappedByChunkSize() throws Exception {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(plan(2_000_000_000L));
        MockHttpServletRequest request = uploadRequest(
                "POST", "/api/files/chunks/init", 2L * 1024 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }
}
