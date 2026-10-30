package com.bond.auth;

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
 * Mapper 扫描范围守护测试（task-129，bond-auth 侧）。
 *
 * <p>与 bond-transfer 的 {@code MapperScanGuardTest} 同一思路：不启动 Spring、不依赖 DB，
 * 静态扫描所有 {@link BaseMapper} 子接口，断言其包名等于 {@code @MapperScan} 声明值。
 * 生产事故的根因是「Mapper 放在扫描包外」，此测试可在单测阶段直接拦下。</p>
 */
class MapperScanGuardTest {

    private static final String SCAN_PACKAGE = "com.bond.auth.mapper";
    private static final String CLASS_PATTERN = "classpath*:com/bond/auth/**/*Mapper.class";

    @Test
    @DisplayName("BondAuthApplication 的 @MapperScan 必须恰好是约定包")
    void applicationDeclaresExpectedMapperScan() {
        MapperScan scan = BondAuthApplication.class.getAnnotation(MapperScan.class);
        assertNotNull(scan, "BondAuthApplication 必须声明 @MapperScan");

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
    @DisplayName("所有 BaseMapper 子接口都必须位于 @MapperScan 扫描包内")
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
            if (!type.isInterface() || !BaseMapper.class.isAssignableFrom(type)) {
                continue;
            }
            seen.add(type);
            if (!SCAN_PACKAGE.equals(type.getPackageName())) {
                offenders.add(className + " → " + type.getPackageName());
            }
        }

        assertTrue(seen.size() >= 5,
                "至少应扫描到若干 BaseMapper 子接口（实际 " + seen.size() + "）：检查 classpath 是否包含 target/classes");
        assertTrue(offenders.isEmpty(),
                "以下 Mapper 不在 @MapperScan 范围内（生产会启动失败）: " + offenders);
    }

    /** URL 形如 file:/.../target/classes/com/bond/auth/mapper/XxxMapper.class → 类名。 */
    private static String classNameOf(Resource resource) throws Exception {
        String path = resource.getURL().getPath();
        int index = path.indexOf("com/bond/auth/");
        if (index < 0) {
            return null;
        }
        String relative = path.substring(index, path.length() - ".class".length());
        return relative.replace('/', '.');
    }
}
