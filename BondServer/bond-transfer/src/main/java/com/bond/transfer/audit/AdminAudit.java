package com.bond.transfer.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「需要写入管理操作审计日志」的管理端写接口（任务单 #19）。
 *
 * <p>由 {@link AdminAuditAspect} 在方法成功返回后自动记录：操作人、动作、目标、
 * 请求体（敏感字段整体省略）、客户端 IP。审计写失败只告警，不影响业务结果。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    /** 动作名，如 user.plan.set / config.update / announcement.create。 */
    String action();

    /** 目标类型，如 user / config / announcement / transfer / workspace。 */
    String targetType() default "";
}
