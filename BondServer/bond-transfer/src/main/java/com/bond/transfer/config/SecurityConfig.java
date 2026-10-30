package com.bond.transfer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * WebDAV 自定义方法（PROPFIND/MKCOL/MOVE/COPY/LOCK/UNLOCK 等）不是 HTTP 标准方法，
     * Spring Security 的 {@link StrictHttpFirewall} 默认只放行
     * DELETE/GET/HEAD/OPTIONS/PATCH/POST/PUT，其余方法在进入 servlet 之前就被拒绝，
     * 由 Spring Security 输出 400 空响应——表现为“WebDAV 全方法 400，但 PATCH 正常”。
     *
     * <p>这里显式放行 WebDAV 全方法集，但保留 firewall 本身（不整体关掉安全链）：
     * TRACE、FOOBAR 等其余方法仍会被拒绝，保持最小放行面。</p>
     *
     * <p>注意：只声明 HttpFirewall bean 在 Spring Security 6.4 的注解配置下并不会自动
     * 生效，还需要通过 {@link WebSecurityCustomizer} 把它装到 {@code WebSecurity} 上，
     * 否则 production 仍会出现 400 空体。</p>
     */
    @Bean
    public HttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(Set.of(
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
                "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"));
        return firewall;
    }

    /**
     * 把上面的 firewall 真正应用到 Security 的 FilterChainProxy 上。
     * 有了它，PROPFIND/MKCOL 等请求才会进入 DispatcherServlet 而不是被 Spring Security
     * 在 servlet 之前拒成 400。
     */
    @Bean
    public WebSecurityCustomizer webDavHttpFirewallCustomizer(HttpFirewall httpFirewall) {
        return web -> web.httpFirewall(httpFirewall);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
