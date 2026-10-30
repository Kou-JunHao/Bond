package com.bond.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtils {

    /** JWT 校验结果分类：网关据此返回可区分的中文 401（task-126）。 */
    public enum TokenState {
        /** 签名有效且未过期。 */
        VALID,
        /** 签名有效但已过期。 */
        EXPIRED,
        /** 结构不是合法 JWT。 */
        MALFORMED,
        /** 签名不匹配（不是本服务签发/被篡改）。 */
        SIGNATURE_INVALID,
        /** 其它 jjwt 校验失败（claim 缺失等）。 */
        INVALID
    }

    private static final String SECRET = System.getenv().getOrDefault("JWT_SECRET", "dev-only-change-in-production");
    private static final long ACCESS_TOKEN_EXPIRE = Long.parseLong(System.getenv().getOrDefault("JWT_ACCESS_EXPIRE_HOURS", "24")) * 60 * 60 * 1000L;
    private static final long REFRESH_TOKEN_EXPIRE = Long.parseLong(System.getenv().getOrDefault("JWT_REFRESH_EXPIRE_DAYS", "7")) * 24 * 60 * 60 * 1000L;

    private static SecretKey getKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    public static String generateAccessToken(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRE))
                .signWith(getKey())
                .compact();
    }

    public static String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRE))
                .signWith(getKey())
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public static boolean isTokenValid(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 不抛出异常地判断 token 状态：网关用它区分「过期」与「无效」，给客户端可操作提示。
     */
    public static TokenState inspect(String token) {
        if (token == null || token.isBlank()) {
            return TokenState.MALFORMED;
        }
        try {
            Claims claims = parseToken(token);
            Date expiration = claims.getExpiration();
            return expiration != null && expiration.after(new Date())
                    ? TokenState.VALID
                    : TokenState.EXPIRED;
        } catch (ExpiredJwtException e) {
            return TokenState.EXPIRED;
        } catch (MalformedJwtException e) {
            return TokenState.MALFORMED;
        } catch (SignatureException e) {
            return TokenState.SIGNATURE_INVALID;
        } catch (IllegalArgumentException e) {
            return TokenState.MALFORMED;
        } catch (JwtException e) {
            return TokenState.INVALID;
        }
    }

    /**
     * 生成带自定义有效期的 access token（测试/内部工具用；负值可造过期 token）。
     */
    public static String generateToken(Long userId, String username, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(getKey())
                .compact();
    }
}
