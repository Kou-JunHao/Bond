package com.bond.transfer.service;

/**
 * 工作区配额对账查询结果行（task-162）。
 *
 * <p>字段由 {@code WorkspaceMapper.selectUsageAudit} 的 snake_case 别名映射
 * （MyBatis-Plus 默认 {@code mapUnderscoreToCamelCase=true}）。</p>
 */
public class UsageAuditRow {

    private Long workspaceId;
    private Long ownerId;
    private String name;
    /** 当前库里的 used_size。 */
    private long usedSize;
    /** 未删除文件（非目录）字节和。 */
    private long fileBytes;
    /** 未删除文档正文字节和。 */
    private long documentBytes;

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getUsedSize() {
        return usedSize;
    }

    public void setUsedSize(long usedSize) {
        this.usedSize = usedSize;
    }

    public long getFileBytes() {
        return fileBytes;
    }

    public void setFileBytes(long fileBytes) {
        this.fileBytes = fileBytes;
    }

    public long getDocumentBytes() {
        return documentBytes;
    }

    public void setDocumentBytes(long documentBytes) {
        this.documentBytes = documentBytes;
    }
}
