package com.bond.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 廉价防护（不启动 Spring 容器）：bond-auth 里所有 Spring bean 若声明了多个构造器，
 * 必须恰好有一个标注 {@code @Autowired} 且为 public，否则 Spring 找无参构造器直接失败。
 *
 * <p>背景：task-57 的 {@code VerificationCodeService}（public 3 参生产构造器 + 包级 4 参测试构造器）
 * 漏标 {@code @Autowired}，单测全绿但生产 bond-auth 启动 crash-loop（502/503 约 5 分钟）。
 * transfer 模块早有同类测试 {@code SpringBeanConstructorTest}，本次把 bond-auth 补齐。</p>
 */
class SpringBeanConstructorTest {

    private static final String BASE_PACKAGE = "com.bond.auth";

    @Test
    @DisplayName("bond-auth 所有 @Component 多构造器 bean 恰好一个 public @Autowired 构造器")
    void multiConstructorBeansDeclareExactlyOneAutowired() throws Exception {
        Set<BeanDefinition> candidates = scanComponents();
        assertFalse(candidates.isEmpty(), "扫描不到任何 @Component，测试失去意义");

        for (BeanDefinition definition : candidates) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            long annotated = Arrays.stream(constructors)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .count();
            assertEquals(1, annotated,
                    type.getSimpleName() + " 有 " + constructors.length + " 个构造器，但标 @Autowired 的为 "
                            + annotated + "：Spring 会回退无参构造并启动失败（参考 pitfalls #20）");
            Constructor<?> springConstructor = Arrays.stream(constructors)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .findFirst()
                    .orElseThrow();
            assertTrue(Modifier.isPublic(springConstructor.getModifiers()),
                    type.getSimpleName() + " 的 @Autowired 构造器应为 public");
        }
    }

    private Set<BeanDefinition> scanComponents() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        // @Component 元注解覆盖 @Service/@Controller/@RestController/@Configuration
        provider.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        return provider.findCandidateComponents(BASE_PACKAGE);
    }
}
