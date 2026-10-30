package com.bond.transfer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper 扫描范围守护测试（task-129）。
 *
 * <p>事故复盘：{@code WebDavLockMapper} 曾放在 {@code com.bond.transfer.webdav}，
 * 而 {@link BondTransferApplication} 是 {@code @MapperScan("com.bond.transfer.mapper")}。
 * 存在 {@code @MapperScan} 时 MyBatis 不再扫描其它包的 {@code @Mapper}，
 * 生产启动报 {@code No qualifying bean of type 'WebDavLockMapper'}。单测全绿也拦不住，
 * 因为相关单测走内存实现、没有全上下文启动。</p>
 *
 * <p>本测试选择「轻量静态守卫」：不启动 Spring、不依赖 DB，直接扫描 classpath 中所有
 * {@link BaseMapper} 子接口并断言其包名。成本最低，且能精确复现事故形态。</p>
 */
class MapperScanGuardTest {

    private static final String SCAN_PACKAGE = "com.bond.transfer.mapper";
    private static final String CLASS_PATTERN = "classpath*:com/bond/transfer/**/*Mapper.class";

    @Test
    @DisplayName("BondTransferApplication 的 @MapperScan 必须恰好是约定包")
    void applicationDeclaresExpectedMapperScan() {
        MapperScan scan = BondTransferApplication.class.getAnnotation(MapperScan.class);
        assertNotNull(scan, "BondTransferApplication 必须声明 @MapperScan");

        Set<String> declared = new LinkedHashSet<>();
        for (String value : scan.value()) {
            if (value != null && !value.isBlank()) {
                declared.add(value.trim());
            }
        }
        for (String value : scan.basePackages()) {
            if (value != null && !value.isBlank()) {
                declared.add(value.trim());
            }
        }
        assertArrayEquals(new String[]{SCAN_PACKAGE}, declared.toArray(new String[0]),
                "@MapperScan 必须只扫描 " + SCAN_PACKAGE + "，实际: " + declared);
    }

    @Test
    @DisplayName("所有 MyBatis Mapper 接口（BaseMapper 子接口或 @Mapper 注解）都必须位于 @MapperScan 扫描包内")
    void everyMapperInterfaceLivesUnderScannedPackage() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(CLASS_PATTERN);

        List<String> offenders = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Resource resource : resources) {
            String className = classNameOf(resource);
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            boolean isMapper = BaseMapper.class.isAssignableFrom(type)
                    || type.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class);
            if (!type.isInterface() || !isMapper) {
                continue;
            }
            seen.add(type);
            if (!SCAN_PACKAGE.equals(type.getPackageName())) {
                offenders.add(className + " → " + type.getPackageName());
            }
        }

        assertTrue(seen.size() >= 3,
                "至少应扫描到若干 MyBatis Mapper 接口（实际 " + seen.size() + "）：检查 classpath 是否包含 target/classes");
        assertTrue(offenders.isEmpty(),
                "以下 Mapper 不在 @MapperScan 范围内（生产会启动失败）: " + offenders);
    }

    @Test
    @DisplayName("事故相关的关键 Mapper 必须存在且在扫描包内")
    void keyMappersAreScanned() throws Exception {
        String[] required = {
                "com.bond.transfer.mapper.WebDavLockMapper",
                "com.bond.transfer.mapper.WorkspaceFileMapper",
                "com.bond.transfer.mapper.EntitlementMapper"
        };
        for (String name : required) {
            Class<?> type = Class.forName(name);
            assertEquals(SCAN_PACKAGE, type.getPackageName(), name + " 必须在 " + SCAN_PACKAGE);
            boolean isMapper = BaseMapper.class.isAssignableFrom(type)
                    || type.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class);
            assertTrue(isMapper, name + " 必须是 BaseMapper 子接口或带 @Mapper 注解");
        }
    }

    /** URL 形如 file:/.../target/classes/com/bond/transfer/mapper/XxxMapper.class → 类名。 */
    private static String classNameOf(Resource resource) throws Exception {
        String path = resource.getURL().getPath();
        int index = path.indexOf("com/bond/transfer/");
        if (index < 0) {
            return null;
        }
        String relative = path.substring(index, path.length() - ".class".length());
        return relative.replace('/', '.');
    }
}
