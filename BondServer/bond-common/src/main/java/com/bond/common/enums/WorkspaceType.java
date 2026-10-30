package com.bond.common.enums;

/**
 * 工作区类型：团队工作区 / 个人空间。
 *
 * <p>个人空间复用 {@code workspaces} 表与 {@code workspace_files} 表，仅通过本类型字段区分，
 * 数据库层面用部分唯一索引保证「每个用户恰好一个个人空间」。</p>
 */
public enum WorkspaceType {
    TEAM("team"),
    PERSONAL("personal");

    private final String value;

    WorkspaceType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
