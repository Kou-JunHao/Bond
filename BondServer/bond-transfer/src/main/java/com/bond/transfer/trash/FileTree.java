package com.bond.transfer.trash;

import com.bond.common.entity.WorkspaceFile;

/**
 * 回收站/文件树的纯路径工具（不依赖 Spring，便于单测）。
 *
 * <p>目录身份 = 其「父路径 + 名称」拼出的绝对逻辑路径，例如 {@code /docs}、
 * {@code /docs/2024}；子项行的 {@code parent_path} 等于该目录身份。</p>
 */
public final class FileTree {

    private FileTree() {
    }

    /** 路径规范化：空 → 根路径 {@code "/"}。 */
    public static String normalizePath(String parentPath) {
        return parentPath == null || parentPath.isBlank() ? "/" : parentPath;
    }

    /** 文件/目录在其父路径下的完整逻辑路径（文件名里的 "/" 不合法，由上传层校验）。 */
    public static String joinPath(String parentPath, String name) {
        String parent = normalizePath(parentPath);
        String safeName = name == null ? "" : name;
        return "/".equals(parent) ? "/" + safeName : parent + "/" + safeName;
    }

    /** 目录的子树基准路径（其后代的 parent_path 等于它或以它 + "/" 开头）。 */
    public static String childBase(WorkspaceFile dir) {
        return joinPath(dir.getParentPath(), dir.getFileName());
    }

    /** 行是否位于给定目录之下（含直接子项）。 */
    public static boolean isUnder(WorkspaceFile row, String dirBase) {
        String parent = normalizePath(row.getParentPath());
        return parent.equals(dirBase) || parent.startsWith(dirBase + "/");
    }

    /** 行的原始完整路径（回收站展示用）。 */
    public static String originalPath(WorkspaceFile row) {
        return joinPath(row.getParentPath(), row.getFileName());
    }
}
