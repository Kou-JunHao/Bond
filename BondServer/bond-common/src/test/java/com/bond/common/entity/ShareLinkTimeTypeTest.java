package com.bond.common.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归防护：V4 {@code share_links} 的 4 个时间列是 TIMESTAMPTZ，实体字段必须一律是
 * {@link OffsetDateTime}（PG JDBC 不允许 getObject(TIMESTAMPTZ) → LocalDateTime，
 * 生产曾因此 500）。V3 新表同样按 TIMESTAMPTZ + OffsetDateTime 约定。
 */
class ShareLinkTimeTypeTest {

    @Test
    @DisplayName("share_links 实体的 4 个时间列均为 OffsetDateTime")
    void shareLinkTimeFieldsAreOffsetDateTime() {
        List<String> timeFields = List.of("codeLockedUntil", "expiresAt", "createdAt", "updatedAt");
        for (String name : timeFields) {
            Field field = field(name);
            assertEquals(OffsetDateTime.class, field.getType(),
                    "share_links." + name + " 必须是 OffsetDateTime（TIMESTAMPTZ 映射）");
        }
        assertFalse(Modifier.isStatic(field("expiresAt").getModifiers()));
    }

    @Test
    @DisplayName("用户档位到期时间 plan_expires_at 为 OffsetDateTime")
    void userPlanExpiresAtIsOffsetDateTime() throws Exception {
        assertEquals(OffsetDateTime.class, User.class.getDeclaredField("planExpiresAt").getType());
        assertTrue(Modifier.isPrivate(User.class.getDeclaredField("planExpiresAt").getModifiers()));
    }

    private static Field field(String name) {
        try {
            return ShareLink.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("ShareLink 缺少字段 " + name, e);
        }
    }
}
