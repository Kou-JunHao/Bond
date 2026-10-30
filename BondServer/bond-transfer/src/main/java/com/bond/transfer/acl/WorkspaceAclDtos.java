package com.bond.transfer.acl;

import java.util.List;

/**
 * 工作区 ACL 的请求/响应 DTO（task-183B）。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /api/workspaces/{id}/members/{userId}/acl}（owner/admin）→ {@link View}；</li>
 *   <li>{@code PUT /api/workspaces/{id}/members/{userId}/acl}（owner/admin）body {@link PutRequest}，
 *       <b>整包替换</b>语义：请求里没有的授权即被删除；{@code accessMode=all} 时清空全部授权；</li>
 *   <li>{@code GET /api/workspaces/{id}/acl/me} → {@link View}（本人有效范围，客户端据此渲染）。</li>
 * </ul>
 */
public final class WorkspaceAclDtos {

    private WorkspaceAclDtos() {
    }

    /**
     * 一条授权。
     *
     * @param scopeType folder / file
     * @param scopePath 规范化逻辑路径（{@code /} 开头、无尾斜杠；folder 下 {@code /} 表示整空间）；
     *                  file 授权时服务端以文件行真实路径为准，忽略客户端传值
     * @param fileId    scopeType=file 时必填；folder 时必须为 null
     * @param canRead   读权限
     * @param canWrite  写权限（服务端会强制同时置 canRead=true）
     */
    public record Grant(String scopeType, String scopePath, Long fileId, boolean canRead, boolean canWrite) {
    }

    /** 成员 ACL 视图。{@code accessMode=all} 时 {@code grants} 恒为空（all 忽略授权表）。 */
    public record View(Long workspaceId, Long userId, String accessMode, List<Grant> grants) {
    }

    /** 整包替换请求；{@code accessMode} 缺省按 all（并清空授权）。 */
    public record PutRequest(String accessMode, List<Grant> grants) {
    }
}
