package com.bond.common.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 真实客户端 IP 解析（task-68）。
 *
 * <p>服务位于 nginx / 网关之后时 {@code getRemoteAddr()} 只会得到代理容器 IP，必须结合代理头
 * 才能拿到真实客户端 IP；但代理头可被客户端伪造，因此**只信任来自已知代理网段的请求头**：
 * 当且仅当直连来源（{@code remoteAddr}）属于 {@link #isTrusted(String) 信任代理} 时，
 * 才采用 {@code X-Real-IP} / {@code X-Forwarded-For}。</p>
 *
 * <p>优先级说明：先取 {@code X-Real-IP}（nginx 通常用 {@code $remote_addr} 覆盖写入，客户端无法伪造），
 * 再取 {@code X-Forwarded-For} 里**最右侧的非可信跳**（从右往左跳过已知代理；左侧是客户端可随意伪造的值，
 * 只信任右侧由我们自己的代理追加的跳，见 task-146）。两者都缺失时退回直连地址。</p>
 *
 * <p>配置格式：逗号分隔的 IP 或 IPv4 CIDR，例如 {@code 127.0.0.1,::1,172.18.0.0/16}。
 * 网关（bond.gateway.trusted-proxies）与 auth（bond.auth.trusted-proxies）分别可覆盖；
 * 生产通过 compose 环境变量 {@code BOND_GATEWAY_TRUSTED_PROXIES} / {@code BOND_AUTH_TRUSTED_PROXIES} 注入。</p>
 */
public final class ClientIpResolver {

    /** 默认信任：本机回环 + 1panel-network 等 Docker 默认桥接网段（nginx/网关容器在此网段）。 */
    public static final String DEFAULT_TRUSTED_PROXIES = "127.0.0.1,::1,172.18.0.0/16";

    private static final String IPV4_MAPPED_PREFIX = "::ffff:";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";

    private final List<String> exactAddresses = new ArrayList<>();
    private final List<Ipv4Range> ipv4Ranges = new ArrayList<>();

    public ClientIpResolver(String trustedProxiesCsv) {
        String csv = trustedProxiesCsv == null || trustedProxiesCsv.isBlank()
                ? DEFAULT_TRUSTED_PROXIES : trustedProxiesCsv;
        for (String raw : csv.split(",")) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int slash = entry.indexOf('/');
            if (slash > 0) {
                Integer network = parseIpv4(entry.substring(0, slash).trim());
                Integer prefix = parsePrefix(entry.substring(slash + 1).trim());
                if (network != null && prefix != null) {
                    ipv4Ranges.add(new Ipv4Range(network, prefix));
                    continue;
                }
            }
            String ip = normalize(entry);
            if (!ip.isEmpty()) {
                exactAddresses.add(ip);
            }
        }
    }

    /**
     * 解析客户端 IP。
     *
     * @param remoteAddr   TCP 直连来源（Servlet 的 getRemoteAddr / Netty 的 remoteAddress）
     * @param xForwardedFor 请求头 X-Forwarded-For（可空）
     * @param xRealIp       请求头 X-Real-IP（可空）
     * @return 规范化后的客户端 IP；全部缺失时返回空串
     */
    public String resolve(String remoteAddr, String xForwardedFor, String xRealIp) {
        String remote = normalize(remoteAddr);
        if (remote.isEmpty()) {
            return "";
        }
        if (!isTrusted(remote)) {
            // 非信任来源：客户端自己写的代理头一律不采信，直接用直连地址。
            return remote;
        }
        String realIp = validHeaderIp(xRealIp);
        if (!realIp.isEmpty()) {
            return realIp;
        }
        String fromXff = rightmostNonTrusted(xForwardedFor);
        if (!fromXff.isEmpty()) {
            return fromXff;
        }
        return remote;
    }

    /**
     * 是否为「内部地址」（回环 / 私有网段 / 链路本地 / ULA）。
     *
     * <p>用于两件事：①网关与 auth 发现 IP 链路退化（外部请求却解析成内部地址）时打 WARN；
     * ②fail2ban 永不封禁代理自身地址，避免把网关/nginx 封掉导致全网 429（task-146）。</p>
     */
    public static boolean isInternalIp(String candidate) {
        String ip = normalize(candidate);
        if (ip.isEmpty()) {
            return false;
        }
        if (ip.indexOf(':') >= 0) {
            String lower = ip.toLowerCase(Locale.ROOT);
            return "::1".equals(lower) || lower.startsWith("fc") || lower.startsWith("fd")
                    || lower.startsWith("fe80:") || lower.startsWith("fe8")
                    || lower.startsWith("fe9") || lower.startsWith("fea") || lower.startsWith("feb");
        }
        Integer v4 = parseIpv4(ip);
        if (v4 == null) {
            return false;
        }
        // 127.0.0.0/8
        if (((v4 >>> 24) & 0xFF) == 127) {
            return true;
        }
        // 10.0.0.0/8
        if (((v4 >>> 24) & 0xFF) == 10) {
            return true;
        }
        // 172.16.0.0/12
        if (((v4 >>> 24) & 0xFF) == 172 && (((v4 >>> 16) & 0xFF) >= 16) && (((v4 >>> 16) & 0xFF) <= 31)) {
            return true;
        }
        // 192.168.0.0/16
        if (((v4 >>> 24) & 0xFF) == 192 && (((v4 >>> 16) & 0xFF) == 168)) {
            return true;
        }
        // 169.254.0.0/16（链路本地，含云厂商元数据地址 169.254.169.254）
        return ((v4 >>> 24) & 0xFF) == 169 && (((v4 >>> 16) & 0xFF) == 254);
    }

    /** 该地址是否属于配置的信任代理（IP 或 IPv4 CIDR）。 */
    public boolean isTrusted(String candidate) {
        String ip = normalize(candidate);
        if (ip.isEmpty()) {
            return false;
        }
        if (exactAddresses.contains(ip)) {
            return true;
        }
        Integer v4 = parseIpv4(ip);
        if (v4 == null) {
            return false;
        }
        for (Ipv4Range range : ipv4Ranges) {
            if (range.contains(v4)) {
                return true;
            }
        }
        return false;
    }

    /** 请求头名常量（供网关/服务端统一引用）。 */
    public static String forwardedForHeader() {
        return FORWARDED_FOR;
    }

    public static String realIpHeader() {
        return REAL_IP;
    }

    /**
     * 取 X-Forwarded-For 中**最右侧的非可信跳**：从右往左解析，跳过已知代理（nginx/网关自身追加的跳），
     * 第一个非可信地址即「最后一个可信代理看到的真实客户端」，左侧客户端可伪造的条目一律不采信。
     */
    private String rightmostNonTrusted(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return "";
        }
        String[] parts = xForwardedFor.split(",");
        String lastValid = "";
        for (int i = parts.length - 1; i >= 0; i--) {
            String ip = validHeaderIp(parts[i]);
            if (ip.isEmpty()) {
                continue;
            }
            if (lastValid.isEmpty()) {
                lastValid = ip;
            }
            if (!isTrusted(ip)) {
                return ip;
            }
        }
        return lastValid;
    }

    /**
     * 校验转发头里的候选值确实是 IP 字面量：非法内容（如 {@code not-an-ip}）一律忽略并回退，
     * 避免把客户端乱填的字符串当成 IP 参与限流键构造。
     */
    private static String validHeaderIp(String value) {
        String ip = normalize(value);
        if (ip.isEmpty()) {
            return "";
        }
        if (ip.indexOf(':') >= 0) {
            return isValidIpv6Literal(ip) ? ip : "";
        }
        return parseIpv4(ip) != null ? ip : "";
    }

    /** 只接受 IPv6 字面量（已由正则排除主机名字符，不会触发 DNS 解析）。 */
    private static boolean isValidIpv6Literal(String ip) {
        if (!ip.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 去除端口、方括号、IPv6 zone、IPv4-mapped 前缀，并 trim。 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String ip = value.trim();
        if (ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            return "";
        }
        if (ip.startsWith("[") && ip.indexOf(']') > 0) {
            ip = ip.substring(1, ip.indexOf(']'));
        } else {
            int colon = ip.lastIndexOf(':');
            int firstColon = ip.indexOf(':');
            // IPv4:port（只有一个冒号）时去掉端口；IPv6 里有多个冒号，保持原样。
            if (colon > 0 && firstColon == colon && ip.indexOf('.') > 0) {
                ip = ip.substring(0, colon);
            }
        }
        int zone = ip.indexOf('%');
        if (zone > 0) {
            ip = ip.substring(0, zone);
        }
        if (ip.toLowerCase(Locale.ROOT).startsWith(IPV4_MAPPED_PREFIX)) {
            ip = ip.substring(IPV4_MAPPED_PREFIX.length());
        }
        return ip;
    }

    private static Integer parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int result = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    private static Integer parsePrefix(String value) {
        try {
            int prefix = Integer.parseInt(value);
            return prefix >= 0 && prefix <= 32 ? prefix : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Ipv4Range(int network, int prefix) {
        boolean contains(int address) {
            if (prefix == 0) {
                return true;
            }
            int mask = (int) (0xFFFFFFFFL << (32 - prefix));
            return (address & mask) == (network & mask);
        }
    }
}
