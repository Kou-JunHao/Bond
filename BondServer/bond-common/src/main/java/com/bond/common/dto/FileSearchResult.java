package com.bond.common.dto;

import java.util.List;

/**
 * task-117：文件搜索结果。
 *
 * @param items      本页结果（按「名称前缀命中优先 → updated_at DESC → id DESC」）
 * @param nextCursor 下一页游标（keyset：updatedAt+id）；{@code hasMore=false} 时为 null
 * @param hasMore    是否还有下一页
 * @param total      命中总数（与 cursor 无关，独立 count 查询）
 */
public record FileSearchResult(List<FileSearchItem> items, String nextCursor, boolean hasMore, long total) {
}
