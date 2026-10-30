package com.bond.auth.config;

import com.bond.common.security.ClientIpResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端 IP 解析器（task-68）：服务端只信任来自 nginx / 网关网段的代理头。
 *
 * <p>默认信任 {@code 127.0.0.1,::1,172.18.0.0/16}（本机与 1panel-network），
 * 可用环境变量 {@code BOND_AUTH_TRUSTED_PROXIES} 覆盖（逗号分隔 IP / IPv4 CIDR）。</p>
 */
@Configuration
public class ClientIpConfig {

    @Bean
    public ClientIpResolver clientIpResolver(
            @Value("${bond.auth.trusted-proxies:" + ClientIpResolver.DEFAULT_TRUSTED_PROXIES + "}") String trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }
}
