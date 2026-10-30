package com.bond.transfer.trash;

import com.bond.common.entity.WorkspaceFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回收站路径工具（纯函数）。 */
class FileTreeTest {

    @Test
    void normalizeRoots() {
        assertEquals("/", FileTree.normalizePath(null));
        assertEquals("/", FileTree.normalizePath(""));
        assertEquals("/", FileTree.normalizePath("  "));
        assertEquals("/docs", FileTree.normalizePath("/docs"));
    }

    @Test
    void joinsPaths() {
        assertEquals("/a", FileTree.joinPath("/", "a"));
        assertEquals("/docs/a.pdf", FileTree.joinPath("/docs", "a.pdf"));
        assertEquals("/a", FileTree.joinPath(null, "a"));
    }

    @Test
    void childBaseOfDirectory() {
        WorkspaceFile dir = new WorkspaceFile();
        dir.setParentPath("/");
        dir.setFileName("docs");
        assertEquals("/docs", FileTree.childBase(dir));

        dir.setParentPath("/root");
        dir.setFileName("docs");
        assertEquals("/root/docs", FileTree.childBase(dir));
    }

    @Test
    void underCheckIsPrefixBoundarySafe() {
        WorkspaceFile file = new WorkspaceFile();
        file.setParentPath("/docs");
        file.setFileName("a.pdf");
        assertTrue(FileTree.isUnder(file, "/docs"));

        file.setParentPath("/docs/2024");
        assertTrue(FileTree.isUnder(file, "/docs"));

        // 不能把 /docs2 当成 /docs 的子项
        file.setParentPath("/docs2");
        assertFalse(FileTree.isUnder(file, "/docs"));
    }

    @Test
    void originalPathIncludesName() {
        WorkspaceFile file = new WorkspaceFile();
        file.setParentPath("/docs");
        file.setFileName("a.pdf");
        assertEquals("/docs/a.pdf", FileTree.originalPath(file));
    }
}
