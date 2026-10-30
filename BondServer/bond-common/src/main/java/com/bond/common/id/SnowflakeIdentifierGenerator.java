package com.bond.common.id;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.bond.common.utils.SnowflakeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 自定义 ID 生成器：把 {@code IdType.ASSIGN_ID} 统一委托给 {@link SnowflakeId}。
 *
 * <p>Spring Boot 自动配置 {@code MybatisPlusAutoConfiguration} 会查找唯一的
 * {@code IdentifierGenerator} Bean 并注入 {@code GlobalConfig}；本类位于
 * {@code com.bond.common} 包，bond-auth / bond-transfer 的启动类均
 * {@code @ComponentScan(basePackages = {..., "com.bond.common"})}，因此会被自动扫描注册。</p>
 *
 * <p>注意：不要在本类里再实现一套雪花算法；所有 ID 必须来自 {@link SnowflakeId}，
 * 保证同一 JVM 内序列号一致、不重复。</p>
 */
@Component
public class SnowflakeIdentifierGenerator implements IdentifierGenerator {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdentifierGenerator.class);

    public SnowflakeIdentifierGenerator() {
        // 启动即触发 SnowflakeId 类初始化并打印 workerId / epoch，便于部署时核对。
        log.info("MyBatis-Plus 使用统一 ID 生成器 SnowflakeIdentifierGenerator: workerId={}, epoch={}",
                SnowflakeId.getWorkerId(), SnowflakeId.getEpoch());
    }

    @Override
    public Number nextId(Object entity) {
        return SnowflakeId.nextId();
    }
}
