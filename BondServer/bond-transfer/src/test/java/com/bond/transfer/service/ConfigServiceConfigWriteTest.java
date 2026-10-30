package com.bond.transfer.service;

import com.bond.common.entity.SystemConfig;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-106：管理端配置写入按 {@code system_config.config_type} 校验，
 * 非法 → 400；批量先全量校验，任何一条非法都不产生写操作。
 */
@ExtendWith(MockitoExtension.class)
class ConfigServiceConfigWriteTest {

    @Mock
    private SystemConfigMapper configMapper;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new ConfigService(configMapper);
        when(configMapper.selectList(null)).thenReturn(List.of());
        configService.refreshCache();
    }

    private static SystemConfig row(String key, String type, String value) {
        SystemConfig config = new SystemConfig();
        config.setConfigKey(key);
        config.setConfigType(type);
        config.setConfigValue(value);
        return config;
    }

    @Test
    @DisplayName("单条：int 键写 \"abc\" → 400 + 键名/期望类型；不落库、不污染缓存")
    void updateRejectsInvalidIntValue() {
        when(configMapper.selectById("upload.max_chunks")).thenReturn(row("upload.max_chunks", "int", "10000"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> configService.update("upload.max_chunks", "abc", 1L));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("upload.max_chunks"));
        assertTrue(e.getMessage().contains("int"));
        verify(configMapper, never()).updateById(any(SystemConfig.class));
        assertEquals(10000, configService.getInt("upload.max_chunks", 10000)); // 缓存未被污染
    }

    @Test
    @DisplayName("单条：boolean 键写 \"on\" → 规范化成 true 落库并刷新缓存")
    void updateNormalizesBooleanBeforePersisting() {
        when(configMapper.selectById("feature.x")).thenReturn(row("feature.x", "boolean", "false"));

        configService.update("feature.x", "on", 7L);

        ArgumentCaptor<SystemConfig> captor = ArgumentCaptor.forClass(SystemConfig.class);
        verify(configMapper).updateById(captor.capture());
        assertEquals("true", captor.getValue().getConfigValue());
        assertEquals(7L, captor.getValue().getUpdatedBy());
        assertTrue(configService.getBoolean("feature.x", false));
    }

    @Test
    @DisplayName("单条：键不存在 → 404")
    void updateMissingKey404() {
        when(configMapper.selectById("nope.key")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> configService.update("nope.key", "1", 1L));

        assertEquals(404, e.getCode());
        verify(configMapper, never()).updateById(any(SystemConfig.class));
    }

    @Test
    @DisplayName("批量：一条非法 → 400，且不产生任何 updateById（先全量校验再写）")
    void batchUpdateAllOrNothingValidation() {
        when(configMapper.selectById("a.int")).thenReturn(row("a.int", "int", "1"));
        when(configMapper.selectById("b.bool")).thenReturn(row("b.bool", "boolean", "true"));

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("a.int", "2");
        updates.put("b.bool", "whatever"); // 非法

        BusinessException e = assertThrows(BusinessException.class,
                () -> configService.batchUpdate(updates, 1L));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("b.bool"));
        verify(configMapper, never()).updateById(any(SystemConfig.class));
        assertEquals(1, configService.getInt("a.int", 1)); // 前一条也不能被写入
    }

    @Test
    @DisplayName("批量：全部合法 → 逐条规范化写入并刷新缓存")
    void batchUpdateWritesNormalizedValues() {
        when(configMapper.selectById("a.int")).thenReturn(row("a.int", "int", "1"));
        when(configMapper.selectById("b.bool")).thenReturn(row("b.bool", "boolean", "false"));

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("a.int", " 42 ");
        updates.put("b.bool", "yes");

        configService.batchUpdate(updates, 1L);

        verify(configMapper, times(2)).updateById(any(SystemConfig.class));
        assertEquals(42, configService.getInt("a.int", 0));
    }

    @Test
    @DisplayName("批量：空/缺键 → 400/404")
    void batchUpdateRejectsEmptyAndUnknownKeys() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> configService.batchUpdate(Map.of(), 1L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> configService.batchUpdate(null, 1L)).getCode());

        when(configMapper.selectById("unknown.key")).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> configService.batchUpdate(Map.of("unknown.key", "1"), 1L));
        assertEquals(404, missing.getCode());
        assertFalse(missing.getMessage().isBlank());
    }
}
