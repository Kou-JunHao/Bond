package com.bond.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 通用分页响应（task-54）。
 *
 * <p>约定：{@code page} 从 1 开始；{@code total} 是满足过滤条件的总行数（独立 count 查询），
 * 不使用「全表读进内存再切」。客户端据此计算是否还有下一页。</p>
 */
@Data
public class PageResult<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setItems(items);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
