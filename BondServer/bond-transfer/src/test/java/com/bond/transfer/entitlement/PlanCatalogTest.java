package com.bond.transfer.entitlement;

import com.bond.transfer.mapper.EntitlementMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * {@link PlanCatalog} 单测：验证启动加载、未知档位回退、失败保留旧快照。
 *
 * <p>缓存本身（TTL / 单飞）由 {@code TtlCacheTest} 覆盖，这里只测目录语义。</p>
 */
@ExtendWith(MockitoExtension.class)
class PlanCatalogTest {

    @Mock
    private EntitlementMapper entitlementMapper;

    @Test
    void loadsCodeToNameMappingOnInit() {
        when(entitlementMapper.selectPlanNames()).thenReturn(List.of(
                Map.of("code", "free", "name", "免费版"),
                Map.of("code", "plus", "name", "增强版")));

        PlanCatalog catalog = new PlanCatalog(entitlementMapper);
        catalog.init();

        assertEquals("免费版", catalog.name("free"));
        assertEquals("增强版", catalog.name("plus"));
    }

    @Test
    void nullNameFallsBackToCode() {
        when(entitlementMapper.selectPlanNames()).thenReturn(List.of(
                Map.of("code", "team")));

        PlanCatalog catalog = new PlanCatalog(entitlementMapper);
        catalog.init();

        assertEquals("team", catalog.name("team"));
    }

    @Test
    void unknownPlanCodeFallsBackToCodeItself() {
        when(entitlementMapper.selectPlanNames()).thenReturn(List.of());

        PlanCatalog catalog = new PlanCatalog(entitlementMapper);
        catalog.init();

        assertEquals("pro", catalog.name("pro"));
        assertNull(catalog.name(null));
    }

    @Test
    void refreshFailureKeepsPreviousSnapshot() {
        when(entitlementMapper.selectPlanNames())
                .thenReturn(List.of(Map.of("code", "free", "name", "免费版")))
                .thenThrow(new IllegalStateException("db down"));

        PlanCatalog catalog = new PlanCatalog(entitlementMapper);
        catalog.init();
        catalog.refresh();

        // 第二次刷新失败，目录保持旧快照
        assertEquals("免费版", catalog.name("free"));
    }
}
