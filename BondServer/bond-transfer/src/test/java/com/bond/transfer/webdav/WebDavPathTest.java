package com.bond.transfer.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebDavPathTest {

    @Test
    void parsesPersonalPathWithEncodedSegments() {
        WebDavPath.Parsed parsed = WebDavPath.parse("/dav/%E6%96%87%E4%BB%B6/photo%201.jpg");

        assertNull(parsed.workspaceId());
        assertEquals(List.of("文件", "photo 1.jpg"), parsed.segments());
        assertEquals("/文件", parsed.parentPath());
        assertEquals("photo 1.jpg", parsed.name());
    }

    @Test
    void parsesWorkspaceMount() {
        WebDavPath.Parsed parsed = WebDavPath.parse("/dav/workspaces/42/docs/a.txt");

        assertEquals(42L, parsed.workspaceId());
        assertEquals(List.of("docs", "a.txt"), parsed.segments());
        assertEquals("/dav/workspaces/42/", WebDavPath.mountHref(parsed.workspaceId()));
    }

    @Test
    void treatsNumericWordAfterWorkspacesAsMountOnlyWhenNumeric() {
        WebDavPath.Parsed parsed = WebDavPath.parse("/dav/workspaces/not-a-number");
        assertNull(parsed.workspaceId());
        assertEquals(List.of("workspaces", "not-a-number"), parsed.segments());
    }

    @Test
    void rootAndTrailingSlash() {
        assertTrue(WebDavPath.parse("/dav").isRoot());
        assertTrue(WebDavPath.parse("/dav/").isRoot());
        assertEquals("/dav/", WebDavPath.mountHref(null));
    }

    @Test
    void rejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> WebDavPath.parse("/dav/%2e%2e/secret"));
        assertThrows(IllegalArgumentException.class, () -> WebDavPath.parse("/dav/../secret"));
        assertThrows(IllegalArgumentException.class, () -> WebDavPath.parse("/dav/a%2Fb"));
        assertThrows(IllegalArgumentException.class, () -> WebDavPath.parse("/dav/a%5Cb"));
    }

    @Test
    void rejectsNonWebDavPath() {
        assertThrows(IllegalArgumentException.class, () -> WebDavPath.parse("/api/files"));
    }

    @Test
    void parsesAbsoluteDestination() {
        WebDavPath.Parsed parsed = WebDavPath.parseDestination(
                "http://127.0.0.1:8082/dav/workspaces/7/b.txt");
        assertEquals(7L, parsed.workspaceId());
        assertEquals(List.of("b.txt"), parsed.segments());

        assertNull(WebDavPath.parseDestination("http://example.com/other/x"));
        assertNull(WebDavPath.parseDestination(null));
    }

    @Test
    void encodesSegmentsForHref() {
        assertEquals("a%20b", WebDavPath.encodeSegment("a b"));
        assertEquals("/a", WebDavPath.joinPath("/", "a"));
        assertEquals("/a/b", WebDavPath.joinPath("/a", "b"));
    }
}
