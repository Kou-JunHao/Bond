package com.bond.common.dto;

import lombok.Data;

/**
 * 新建 WebDAV 专用密码的响应（task-48）。
 *
 * <p>{@code password} 是<b>一次性明文</b>，服务端只存 bcrypt；刷新后无法再次取回，
 * 客户端需要提示用户立即保存。</p>
 */
@Data
public class WebDavPasswordCreatedDTO {
    private Long id;
    private String name;
    /** 一次性明文（URL-safe 随机串，长度 43）。 */
    private String password;
}
