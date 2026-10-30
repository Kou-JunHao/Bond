package com.bond.transfer.trash;

/**
 * 回收站条目（GET /api/trash 的列表项）。
 *
 * <p>只列顶层条目：目录子树里的子项不单独出现（用 itemCount 表示）。
 * 文件与文档共用一个回收站：{@code kind} 区分 {@code file} / {@code directory} / {@code document}。</p>
 *
 * @param id              文件/目录/文档 id
 * @param fileName        原文件名（文档 = 标题）
 * @param fileSize        字节数（目录 = 0；文档 = 正文 UTF-8 字节数）
 * @param isDirectory     是否目录（文档 = false）
 * @param kind            条目类型：file / directory / document
 * @param originalPath    原完整路径（含文件名/标题，如 /docs/2024/a.pdf）
 * @param deletedAtMillis 进回收站时间（epoch 毫秒）
 * @param expiresAtMillis 过期清理时间（epoch 毫秒；null = 不限期）
 * @param itemCount       目录子树里的文件数（非目录 = 0）
 */
public record TrashItem(
        Long id,
        String fileName,
        Long fileSize,
        Boolean isDirectory,
        String kind,
        String originalPath,
        Long deletedAtMillis,
        Long expiresAtMillis,
        long itemCount) {
}
