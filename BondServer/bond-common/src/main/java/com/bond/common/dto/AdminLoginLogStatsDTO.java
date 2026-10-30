package com.bond.common.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端登录统计（task-144，`GET /api/admin/login-logs/stats?days=7`）。
 */
@Data
public class AdminLoginLogStatsDTO {

    private int days;
    private long total;
    private long success;
    private long failed;
    private Map<String, Long> byOutcome = new LinkedHashMap<>();
    private List<IpStat> topIps = new ArrayList<>();
    private List<UserStat> topUsers = new ArrayList<>();
    private List<DailyStat> daily = new ArrayList<>();

    @Data
    public static class IpStat {
        private String ip;
        private long count;
        private long failed;
    }

    @Data
    public static class UserStat {
        private String username;
        private long count;
        private long failed;
    }

    @Data
    public static class DailyStat {
        /** yyyy-MM-dd */
        private String date;
        private long success;
        private long failed;
    }
}
