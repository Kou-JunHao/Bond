package com.bond.transfer.acl;

import com.bond.common.entity.WorkspaceAcl;
import com.bond.transfer.trash.FileTree;

import java.util.List;

/**
 * 一个成员在一个工作区里的**有效访问范围**（task-183B）。
 *
 * <p>由 {@link WorkspaceAclService#resolve} 构造：owner/admin 或 {@code access_mode='all'} 的成员
 * 得到 {@link #isRestricted()}=false（全部放行，与历史行为一致）；{@code restricted} 成员得到
 * 授权行列表，所有判定都在授权行并集上完成。</p>
 *
 * <p>路径口径（与 {@link FileTree} 一致）：</p>
 * <ul>
 *   <li>目录/文件的逻辑路径以 {@code /} 开头、无尾斜杠；{@code "/"} 表示工作区根；</li>
 *   <li>目录授权 {@code /docs} 覆盖 {@code /docs} 本身与 {@code /docs/**}（前缀按路径段匹配，
 *       {@code /docs} <b>不</b>覆盖 {@code /docs2}）；</li>
 *   <li>文件授权只覆盖对应 fileId 的那一行；</li>
 *   <li>允许「向下遍历」：某个授权在目录 {@code /} 之下时，允许列出 {@code /} 以进到授权目录，
 *       但列表里只会出现被授权的子项（由调用方过滤）。</li>
 * </ul>
 */
public final class WorkspaceAccess {

    private final boolean restricted;
    private final List<WorkspaceAcl> grants;

    private WorkspaceAccess(boolean restricted, List<WorkspaceAcl> grants) {
        this.restricted = restricted;
        this.grants = grants;
    }

    /** 不受限（owner/admin / access_mode=all）：所有判定直接放行。 */
    public static WorkspaceAccess unrestricted() {
        return new WorkspaceAccess(false, List.of());
    }

    public static WorkspaceAccess restricted(List<WorkspaceAcl> grants) {
        return new WorkspaceAccess(true, grants == null ? List.of() : List.copyOf(grants));
    }

    public boolean isRestricted() {
        return restricted;
    }

    public List<WorkspaceAcl> grants() {
        return grants;
    }

    // ── 判定：目录 ──

    /**
     * 能否**读取/遍历**该目录路径：被某个目录授权覆盖（含其子路径），或存在某个授权在该目录之下
     * （否则 restricted 成员连「下钻到授权目录」都做不到）。
     */
    public boolean canReadDirectory(String dirPath) {
        if (!restricted) {
            return true;
        }
        String path = normalize(dirPath);
        for (WorkspaceAcl grant : grants) {
            if (!Boolean.TRUE.equals(grant.getCanRead())) {
                continue;
            }
            String scope = normalize(grant.getScopePath());
            if (covers(scope, path) || isUnder(scope, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在**目录授权显式覆盖**该目录（不含「向下遍历」语义）。
     *
     * <p>分享目录时必须用它：只有「授权在目录之下」的遍历权限不能证明成员有权导出整个目录，
     * 否则可以把父目录整包分享，等于越权导出未授权内容。</p>
     */
    public boolean isDirectoryCoveredByGrant(String dirPath) {
        if (!restricted) {
            return true;
        }
        String path = normalize(dirPath);
        for (WorkspaceAcl grant : grants) {
            if (!Boolean.TRUE.equals(grant.getCanRead())
                    || !WorkspaceAclService.SCOPE_FOLDER.equals(grant.getScopeType())) {
                continue;
            }
            if (covers(normalize(grant.getScopePath()), path)) {
                return true;
            }
        }
        return false;
    }

    /** 能否在该目录下**创建/写入**条目：必须有 folder 授权覆盖该路径且 can_write。 */
    public boolean canWriteDirectory(String dirPath) {
        if (!restricted) {
            return true;
        }
        String path = normalize(dirPath);
        for (WorkspaceAcl grant : grants) {
            if (!Boolean.TRUE.equals(grant.getCanWrite()) || !WorkspaceAclService.SCOPE_FOLDER.equals(grant.getScopeType())) {
                continue;
            }
            if (covers(normalize(grant.getScopePath()), path)) {
                return true;
            }
        }
        return false;
    }

    // ── 判定：文件 ──

    /** 能否读取该文件：folder 授权覆盖其路径，或 file 授权命中同一 fileId（且 can_read）。 */
    public boolean canReadFile(Long fileId, String filePath) {
        if (!restricted) {
            return true;
        }
        String path = normalize(filePath);
        for (WorkspaceAcl grant : grants) {
            if (!Boolean.TRUE.equals(grant.getCanRead())) {
                continue;
            }
            if (matchesFileGrant(grant, fileId, path)) {
                return true;
            }
            if (WorkspaceAclService.SCOPE_FOLDER.equals(grant.getScopeType())
                    && covers(normalize(grant.getScopePath()), path)) {
                return true;
            }
        }
        return false;
    }

    /** 能否改写/删除该文件：folder 授权（can_write）覆盖其路径，或 file 授权命中且 can_write。 */
    public boolean canWriteFile(Long fileId, String filePath) {
        if (!restricted) {
            return true;
        }
        String path = normalize(filePath);
        for (WorkspaceAcl grant : grants) {
            if (!Boolean.TRUE.equals(grant.getCanWrite())) {
                continue;
            }
            if (matchesFileGrant(grant, fileId, path)) {
                return true;
            }
            if (WorkspaceAclService.SCOPE_FOLDER.equals(grant.getScopeType())
                    && covers(normalize(grant.getScopePath()), path)) {
                return true;
            }
        }
        return false;
    }

    // ── 路径工具（包内可见，便于单测） ──

    /** 规范化：空 → "/"；补前导 /；去掉尾部 /（根除外）。不做 ".." 解析（上游已校验）。 */
    static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** {@code scope} 是否覆盖 {@code path}（含相等；{@code "/"} 覆盖全部）。按路径段边界匹配。 */
    static boolean covers(String scope, String path) {
        if ("/".equals(scope)) {
            return true;
        }
        return path.equals(scope) || path.startsWith(scope + "/");
    }

    /** {@code scope} 是否位于 {@code path} 之下（严格子路径；用于允许向下遍历）。 */
    static boolean isUnder(String scope, String path) {
        if ("/".equals(path)) {
            return !"/".equals(scope);
        }
        return scope.startsWith(path + "/");
    }

    private static boolean matchesFileGrant(WorkspaceAcl grant, Long fileId, String path) {
        if (!WorkspaceAclService.SCOPE_FILE.equals(grant.getScopeType())) {
            return false;
        }
        if (fileId != null && grant.getFileId() != null && fileId.equals(grant.getFileId())) {
            return true;
        }
        // file_id 行被彻底删除后授权随 FK 级联清空；这里再用路径兜底（防御历史脏数据）
        return grant.getFileId() == null && normalize(grant.getScopePath()).equals(path);
    }
}
