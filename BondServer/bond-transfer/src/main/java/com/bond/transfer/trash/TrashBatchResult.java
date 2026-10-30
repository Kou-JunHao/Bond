package com.bond.transfer.trash;

import java.util.List;

/**
 * 批量恢复/彻底删除的结果。
 *
 * <p>逐条独立执行：单条失败不影响其他条。全部成功时 HTTP 200；有失败时 HTTP 207，
 * body 仍为 Result 结构（code=207），{@code data} 为本次结果。</p>
 *
 * @param succeeded 成功的 id 列表
 * @param failed    失败的 id 与原因
 */
public record TrashBatchResult(List<Long> succeeded, List<Failed> failed) {

    /** 单条失败明细。 */
    public record Failed(Long id, String reason) {
    }

    public boolean hasFailure() {
        return failed != null && !failed.isEmpty();
    }
}
