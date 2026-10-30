package com.bond.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-29：接口 JSON 契约（客户端按字段名解析，isXxx 前缀不能被 Jackson 剥掉）。
 */
class EntitlementMeDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("isThrottled / isStorageFull 按原字段名序列化（不会被剥成 throttled）")
    void isPrefixFlagsKeepTheirNames() throws Exception {
        EntitlementMeDTO dto = new EntitlementMeDTO();
        dto.setPlanCode("free");
        dto.setPlanName("Free");
        dto.setStatus("active");
        dto.setPeriod("2026-10");
        dto.setThrottled(true);
        dto.setStorageFull(false);

        EntitlementMeDTO.Limits limits = new EntitlementMeDTO.Limits();
        limits.setRelayBytesMonth(10737418240L);
        limits.setStorageBytes(5368709120L);
        limits.setMaxFileSize(2147483648L);
        limits.setMaxWorkspaces(1);
        limits.setMaxWorkspaceMembers(3);
        limits.setConcurrentTransfers(2);
        limits.setRelayDownBytesPerSec(5242880L);
        limits.setGraceDays(3);
        limits.setShareExpiresDays(7);
        limits.setShareMaxDownloads(30);
        limits.setShareMaxLinks(5);
        limits.setTrashRetentionDays(7);
        dto.setLimits(limits);

        EntitlementMeDTO.Usage usage = new EntitlementMeDTO.Usage();
        usage.setRelayBytesDown(123456L);
        usage.setStorageBytes(158032007L);
        usage.setFileCount(16L);
        usage.setRelayBytesRemaining(10737294784L);
        usage.setStorageBytesRemaining(5210677113L);
        dto.setUsage(usage);

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"isThrottled\":true"), json);
        assertTrue(json.contains("\"isStorageFull\":false"), json);
        assertFalse(json.contains("\"throttled\":"), "属性名被 Jackson 剥成了 throttled: " + json);
        assertTrue(json.contains("\"relayBytesMonth\":10737418240"), json);
        assertTrue(json.contains("\"relayBytesDown\":123456"), json);
        assertTrue(json.contains("\"relayBytesRemaining\":10737294784"), json);
        // task-115：客户端按 camelCase 读取「按档位保留 N 天」等字段
        assertTrue(json.contains("\"trashRetentionDays\":7"), json);
        assertTrue(json.contains("\"shareMaxDownloads\":30"), json);
        assertTrue(json.contains("\"shareMaxLinks\":5"), json);
        assertFalse(json.contains("trash_retention_days"), "对外键名必须是 camelCase: " + json);
    }
}
