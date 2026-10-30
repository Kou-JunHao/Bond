package com.bond.transfer.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * If-None-Match 与裸实体标签的比对（RFC 9110）。
 *
 * <p>回归背景：服务层产出的标签不带引号，而 {@code ResponseEntity#eTag} 会把它们加上双引号写进
 * 响应头。客户端据此回传的 If-None-Match 是带引号的版本，早先直接 {@code equals} 比对，
 * 导致 304 分支永远不会命中——每次缩略图请求都回 200 全量图，白耗带宽（生产实测确认）。</p>
 */
class EtagMatchTest {

    private static final String ETAG = "thumb-4e89cca7a847ab9a0415d27b5a109c62";

    @Test
    @DisplayName("带引号的回传值与裸标签匹配")
    void matchesQuotedValue() {
        assertTrue(EtagMatch.matches(ETAG, "\"" + ETAG + "\""));
    }

    @Test
    @DisplayName("弱校验前缀 W/ 与首尾空白不影响匹配")
    void matchesWeakValidatorAndWhitespace() {
        assertTrue(EtagMatch.matches(ETAG, "  W/\"" + ETAG + "\"  "));
        assertTrue(EtagMatch.matches(ETAG, "w/" + ETAG));
    }

    @Test
    @DisplayName("逗号分隔的标签列表中任一命中即匹配")
    void matchesAnyInList() {
        assertTrue(EtagMatch.matches(ETAG, "\"other\", \"" + ETAG + "\""));
    }

    @Test
    @DisplayName("通配符 * 视作匹配")
    void matchesWildcard() {
        assertTrue(EtagMatch.matches(ETAG, "*"));
    }

    @Test
    @DisplayName("不匹配、空值、null 均返回 false")
    void rejectsMismatchAndMissing() {
        assertFalse(EtagMatch.matches(ETAG, "\"stale-etag\""));
        assertFalse(EtagMatch.matches(ETAG, ""));
        assertFalse(EtagMatch.matches(ETAG, "   "));
        assertFalse(EtagMatch.matches(ETAG, null));
        assertFalse(EtagMatch.matches(null, "\"" + ETAG + "\""));
    }
}
