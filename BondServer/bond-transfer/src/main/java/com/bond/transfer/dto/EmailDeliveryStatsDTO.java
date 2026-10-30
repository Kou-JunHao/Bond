package com.bond.transfer.dto;

import java.util.List;

/**
 * 管理端「邮件投递统计」（task-80，默认 7 天窗口）。
 *
 * <p>{@code failureRate} 为失败率百分比（0–100，保留 2 位小数；无数据时为 0.0），
 * 字段始终序列化（含 0），避免后台图表取不到值。</p>
 */
public record EmailDeliveryStatsDTO(int days,
                                    long total,
                                    long sent,
                                    long failed,
                                    double failureRate,
                                    List<PurposeStat> byPurpose) {

    /** 单个用途的发送量。 */
    public record PurposeStat(String purpose, long total, long failed) {
    }
}
