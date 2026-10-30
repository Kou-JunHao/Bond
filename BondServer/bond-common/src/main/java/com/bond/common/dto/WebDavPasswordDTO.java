package com.bond.common.dto;

import lombok.Data;

/**
 * WebDAV 专用密码列表项（task-48）。
 *
 * <p><b>永不包含密码 / 哈希</b>：明文只在生成时返回一次，列表与任何查询接口都不回传。</p>
 */
@Data
public class WebDavPasswordDTO {
    private Long id;
    private String name;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 最近一次成功认证时间（epoch 毫秒），从未使用为 null。 */
    private Long lastUsedAt;
    /** 是否已吊销；列表接口只返回未吊销条目，因此恒为 false（保留字段方便客户端统一模型）。 */
    private boolean revoked;
}
