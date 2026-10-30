package com.bond.common.id;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.bond.common.utils.SnowflakeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MyBatis-Plus 适配器存在、可被 Spring 扫描，并且委托给唯一的 SnowflakeId。
 */
class SnowflakeIdentifierGeneratorTest {

    @Test
    @DisplayName("实现 MP IdentifierGenerator 且带 @Component（可被 auth/transfer 的 com.bond.common 扫描）")
    void isSpringComponentAndMpGenerator() {
        SnowflakeIdentifierGenerator generator = new SnowflakeIdentifierGenerator();
        assertInstanceOf(IdentifierGenerator.class, generator);
        assertTrue(SnowflakeIdentifierGenerator.class.isAnnotationPresent(Component.class),
                "SnowflakeIdentifierGenerator must be annotated with @Component so both services pick it up");
    }

    @Test
    @DisplayName("nextId 委托 SnowflakeId：返回 Long、两次调用不同、workerId 位段一致")
    void nextIdDelegatesToSnowflakeId() {
        SnowflakeIdentifierGenerator generator = new SnowflakeIdentifierGenerator();
        Number first = generator.nextId(new Object());
        Number second = generator.nextId(new Object());
        assertNotNull(first);
        assertInstanceOf(Long.class, first);
        assertNotEquals(first, second);
        long firstId = first.longValue();
        assertEquals(SnowflakeId.getWorkerId(),
                (firstId >> SnowflakeId.SEQUENCE_BITS) & SnowflakeId.MAX_WORKER_ID);
    }
}
