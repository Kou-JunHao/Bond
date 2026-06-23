package com.bond.common.dto;

import lombok.Data;
import java.util.Map;

@Data
public class DashboardDTO {
    private long totalUsers;
    private long activeUsers;
    private long totalTransfers;
    private long completedTransfers;
    private long totalWorkspaces;
    private long totalStorageUsed;
    private Map<String, Long> transferTrend;
}
