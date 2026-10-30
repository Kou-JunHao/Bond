package com.bond.transfer;

import com.bond.common.util.TtlCache;
import com.bond.transfer.entitlement.EntitlementMeService;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.PlanCatalog;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.EntitlementAdminService;
import com.bond.transfer.service.RedeemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 廉价防护（不启动 Spring 容器）：Spring bean 有多个构造器时，必须恰好有一个标注
 * {@code @Autowired}，否则 Spring 会回退到默认无参构造并抛
 * {@code NoSuchMethodException: <init>()}，导致整个应用上下文刷新失败。
 *
 * <p>背景：task-30 的 {@code EntitlementMeService}（Spring 构造器 + 测试构造器）漏标
 * {@code @Autowired}，生产环境 bond-transfer 启动崩溃循环；常规单测用 {@code new} 构造实例，
 * 不经过容器，因此抓不到该类问题。这里用反射把这类隐患钉死在本单测里。</p>
 */
class SpringBeanConstructorTest {

    /** 本轮（task-30 Plan A 缓存）改动过、且由 Spring 管理的 bean。 */
    private static final List<Class<?>> SPRING_BEANS = List.of(
            EntitlementMeService.class,
            EntitlementResolver.class,
            PlanCatalog.class,
            ConfigService.class,
            EntitlementAdminService.class,
            RedeemService.class);

    @Test
    @DisplayName("多构造器 bean 必须恰好一个构造器标 @Autowired（且为 public）")
    void multiConstructorBeansDeclareExactlyOneAutowired() {
        for (Class<?> type : SPRING_BEANS) {
            assertTrue(type.isAnnotationPresent(Service.class) || type.isAnnotationPresent(Component.class),
                    type.getSimpleName() + " 应为 Spring bean（@Service/@Component）");

            Constructor<?>[] constructors = type.getDeclaredConstructors();
            long annotated = Arrays.stream(constructors)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .count();

            if (constructors.length > 1) {
                assertEquals(1, annotated,
                        type.getSimpleName() + " 有 " + constructors.length
                                + " 个构造器，但标 @Autowired 的构造器数量为 " + annotated
                                + "：Spring 会回退默认无参构造并启动失败");
                Constructor<?> springConstructor = Arrays.stream(constructors)
                        .filter(c -> c.isAnnotationPresent(Autowired.class))
                        .findFirst()
                        .orElseThrow();
                assertTrue(java.lang.reflect.Modifier.isPublic(springConstructor.getModifiers()),
                        type.getSimpleName() + " 的 @Autowired 构造器应为 public");
            }
        }
    }

    @Test
    @DisplayName("TtlCache 是普通工具类，不应被注册为 Spring bean")
    void ttlCacheIsNotASpringBean() {
        assertFalse(TtlCache.class.isAnnotationPresent(Service.class));
        assertFalse(TtlCache.class.isAnnotationPresent(Component.class));
    }
}
