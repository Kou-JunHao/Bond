package com.bond.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS 白名单（task-126）。
 *
 * <p>task-124 的审计发现：原配置同时用了 {@code allowedOriginPattern("*")} 和
 * {@code allowCredentials(true)}，等价于「任意网站可以带 cookie 读接口」，是真实
 * 安全缺陷。现在改为配置驱动白名单：</p>
 * <ul>
 *   <li>默认只允许本机开发端口与 admin-web 域名；生产用
 *       {@code BOND_CORS_ALLOWED_ORIGINS}（逗号分隔）覆盖；</li>
 *   <li>显式写 {@code *} 时仍然允许（方便临时排障），但**强制关闭 credentials** 并打 WARN，
 *       因为规范禁止二者同时成立；</li>
 *   <li>响应头通过 {@code exposed-headers} 暴露下载/分页需要的字段。</li>
 * </ul>
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /**
     * 未配置时只放行本机开发端口、admin-web 与**自身站点**，宁可开发时手动加，也不默认全开。
     *
     * <p>自身站点（{@code bond-api.sg.skkk.uno} / {@code api.sg.skkk.uno}）必须在内：
     * 分享落地页 {@code GET /s/{token}} 与提取码校验 {@code POST /s/{token}/verify} 是**同源**请求，
     * 但浏览器对 POST 也会带 {@code Origin}；而 nginx TLS 终止后回源是 http，
     * 网关看到的 request scheme 是 http、Origin 是 https → Spring 判定为跨域请求，
     * 不在白名单就 403 空 body（分享页“网络异常”的真实根因，2026-10-29 修复）。</p>
     */
    static final List<String> DEFAULT_ORIGINS = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "https://admin.sg.skkk.uno",
            "https://bond-admin.sg.skkk.uno",
            "https://bond-api.sg.skkk.uno",
            "https://api.sg.skkk.uno");

    static final List<String> ALLOWED_METHODS = List.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    static final List<String> ALLOWED_HEADERS = List.of(
            "Authorization", "Content-Type", "Accept", "Range", "If-Match", "If-None-Match",
            "If-Range", "X-Requested-With", "X-Request-Id");

    static final List<String> EXPOSED_HEADERS = List.of(
            "Content-Disposition", "Content-Range", "X-Total-Pages", "ETag",
            "Retry-After", "WWW-Authenticate", "Location");

    private final String allowedOrigins;

    public CorsConfig(@Value("${bond.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = buildConfiguration(allowedOrigins);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }

    static CorsConfiguration buildConfiguration(String rawOrigins) {
        List<String> origins = resolveOrigins(rawOrigins);
        boolean wildcard = origins.contains("*");

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setMaxAge(3600L);

        if (wildcard) {
            log.warn("CORS: bond.cors.allowed-origins 配置为 '*'，将允许所有来源；"
                    + "出于浏览器安全规范，已强制 allowCredentials=false（不支持携带 cookie）。"
                    + "生产请改为显式白名单。");
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }
        return config;
    }

    static List<String> resolveOrigins(String raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result.isEmpty() ? new ArrayList<>(DEFAULT_ORIGINS) : result;
    }
}
