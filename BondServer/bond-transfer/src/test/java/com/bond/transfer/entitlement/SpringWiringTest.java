package com.bond.transfer.entitlement;

import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 真实 Spring 构造器解析回归测试（不连数据库、不启动完整 Boot 上下文）。
 *
 * <p>task-30 事故：{@link EntitlementMeService} 有多个构造器且都没标 {@code @Autowired}，
 * Spring 回退「默认无参构造」→ {@code NoSuchMethodException: <init>()} → bond-transfer 启动崩溃。
 * 普通的 {@code new} 单测不经过容器，抓不到；这里用最小 {@link AnnotationConfigApplicationContext}
 * 真正走一遍 Spring 的构造器选择逻辑。</p>
 */
class SpringWiringTest {

    @Test
    @DisplayName("Spring 能用 @Autowired 构造器实例化 EntitlementMeService（多构造器安全）")
    void springInstantiatesEntitlementMeService() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(EntitlementResolver.class, () -> mock(EntitlementResolver.class));
            ctx.registerBean(UsageService.class, () -> mock(UsageService.class));
            ctx.registerBean(PersonalSpaceService.class, () -> mock(PersonalSpaceService.class));
            ctx.registerBean(WorkspaceService.class, () -> mock(WorkspaceService.class));
            ctx.registerBean(PlanCatalog.class, () -> mock(PlanCatalog.class));
            ctx.registerBean(ConfigService.class, () -> mock(ConfigService.class));
            ctx.registerBean(EntitlementMeService.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(EntitlementMeService.class));
        }
    }

    @Test
    @DisplayName("Spring 能用 @Autowired 构造器实例化 EntitlementResolver（多构造器安全）")
    void springInstantiatesEntitlementResolver() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(com.bond.transfer.mapper.EntitlementMapper.class,
                    () -> mock(com.bond.transfer.mapper.EntitlementMapper.class));
            ctx.registerBean(com.bond.transfer.mapper.UserMapper.class,
                    () -> mock(com.bond.transfer.mapper.UserMapper.class));
            ctx.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            ctx.registerBean(ConfigService.class, () -> mock(ConfigService.class));
            ctx.registerBean(EntitlementResolver.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(EntitlementResolver.class));
        }
    }
}
