package com.bond.transfer.share;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * 分享链接的请求/响应 DTO（登录接口用 {@code /api/shares}）。
 *
 * <p>匿名路径 {@code /s/{token}} 只消费 {@code ShareLinkView} 中的少数字段，不复用登录态 DTO。</p>
 */
public final class ShareDtos {

    private ShareDtos() {
    }

    /** 创建分享链接请求。{@code workspaceId} 为空时按文件所属空间自动判定。 */
    public record CreateShareRequest(
            Long fileId,
            Long workspaceId,
            Boolean requiresCode,
            String code,
            Integer expiresDays,
            Integer maxDownloads) {
    }

    /**
     * 修改分享链接请求（PATCH，task-163）。
     *
     * <p>三个字段都是「可选但可显式 null」，因此用 {@link JsonNode} 而非普通类型：
     * <ul>
     *   <li>字段**缺失**（node 为 null）= 保持不变；</li>
     *   <li>字段存在且为 JSON {@code null}（{@link JsonNode#isNull()}）= 显式清空；</li>
     *   <li>其余为实际取值。</li>
     * </ul>
     * 语义：{@code extractCode}=null/空串 → 取消提取码；{@code expiresAtMillis}=null → 永久；
     * {@code maxDownloads}=null → 不限制。</p>
     */
    public record PatchShareRequest(JsonNode extractCode, JsonNode expiresAtMillis, JsonNode maxDownloads) {
    }

    /** 提取码校验请求（匿名接口）。 */
    public record VerifyRequest(String code) {
    }

    /** 分享链接视图（不返回 code_hash，避免摘要外泄被离线爆破）。 */
    public record ShareLinkView(
            Long id,
            String token,
            String url,
            Long fileId,
            String fileName,
            Long fileSize,
            boolean requiresCode,
            OffsetDateTime expiresAt,
            int maxDownloads,
            int downloadCount,
            int status,
            OffsetDateTime createdAt,
            /** 派生状态：active / expired / revoked（status=2 或时间已过都算 expired）。 */
            String state,
            /** 最近一次成功下载时间（epoch millis）；从未下载为 null。 */
            Long lastAccessAtMillis,
            /** 创建时间（epoch millis），task-163 客户端统一按毫秒解析。 */
            Long createdAtMillis,
            /** 过期时间（epoch millis）；null = 永久有效。 */
            Long expiresAtMillis,
            /**
             * task-183：是否为文件夹分享（只增不改）。
             *
             * <p>文件分享为 {@code false}（历史链接该字段为 null，客户端按 false 处理）；
             * 文件夹分享为 {@code true}，此时 {@code /s/{token}/download} 返回 ZIP 流。
             * {@code fileSize} 对文件夹为创建时刻的 0（体积以 ZIP 实际下行计）。</p>
             */
            Boolean isDirectory) {
    }
}
