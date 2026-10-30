package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 管理端分享链接治理项（task-89）。
 *
 * <p>只暴露治理所需元数据：<b>不返回文件内容，也不返回任何签名 URL</b>；
 * {@code url} 是按 {@code share.public_base_url} 拼出的公网落地页地址。
 * 时间字段统一为毫秒时间戳（与 AdminUserDTO 等管理端 DTO 一致），
 * 其中 {@code lastAccessAt} 取 {@code share_links.updated_at}（下载计数/撤销等
 * 状态变更都会刷新它），用于近似「最近活动时间」。</p>
 */
@Data
public class AdminShareDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 完整 token（管理员治理所需；admin-web 列表默认只展示前 8 位）。 */
    private String token;

    /** 公网落地页地址：{@code share.public_base_url + "/s/" + token}。 */
    private String url;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    private String fileName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;

    private String ownerUsername;

    /** 0=正常，1=已撤销。 */
    private Integer status;

    private Long expiresAt;
    private Integer downloadCount;
    private Long createdAt;
    private Long lastAccessAt;
}
