package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.storage.ObjectStorage.ObjectStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Office→PDF 预览缓存服务（task-59）单测：缓存命中/转换落缓存/负缓存/503 不缓存/
 * 超限/不支持类型/Range 读取/缓存键回退。
 */
@ExtendWith(MockitoExtension.class)
class OfficePreviewServiceTest {

    private static final byte[] PDF = "%PDF-1.7 preview".getBytes(StandardCharsets.US_ASCII);

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private OfficePdfConverter converter;
    @Mock
    private ConfigService configService;

    private OfficePreviewService service;

    @BeforeEach
    void setUp() {
        service = new OfficePreviewService(objectStorage, converter, configService);
        org.mockito.Mockito.lenient().when(configService.getBoolean(eq("office.preview.enabled"), eq(true))).thenReturn(true);
        org.mockito.Mockito.lenient().when(configService.getInt(eq("office.preview.concurrency"), eq(2))).thenReturn(2);
        org.mockito.Mockito.lenient().when(configService.getInt(eq("office.preview.timeout-seconds"), eq(90))).thenReturn(90);
        org.mockito.Mockito.lenient().when(configService.getLong(eq("office.preview.max-source-bytes"), anyLong()))
                .thenReturn(100L * 1024 * 1024);
        org.mockito.Mockito.lenient().when(configService.get(eq("office.preview.endpoint"), anyString()))
                .thenReturn("http://gotenberg:3000");
        org.mockito.Mockito.lenient().when(configService.getBoolean(eq("office.preview.single-page-sheets"), eq(true)))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(objectStorage.getObject(anyString()))
                .thenAnswer(inv -> new ByteArrayInputStream("doc-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    private static WorkspaceFile file(String name, Long size, String hash) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(5L);
        f.setWorkspaceId(7L);
        f.setFileName(name);
        f.setFileSize(size);
        f.setFileHash(hash);
        f.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        f.setMinioPath("workspaces/7/uuid/" + name);
        f.setIsDirectory(false);
        f.setCreatedAt(LocalDateTime.of(2026, 10, 23, 10, 0));
        return f;
    }

    @Test
    @DisplayName("功能关闭 → DisabledException(404)")
    void disabledThrows404() {
        when(configService.getBoolean(eq("office.preview.enabled"), eq(true))).thenReturn(false);
        OfficePreviewService.DisabledException ex = assertThrows(OfficePreviewService.DisabledException.class,
                () -> service.ensure(file("a.docx", 10L, "abc")));
        assertEquals(404, ex.getStatus());
        verifyNoInteractions(converter, objectStorage);
    }

    @Test
    @DisplayName("非 Office 文件 → UnsupportedTypeException(415)，不触存储/转换")
    void unsupportedTypeThrows415() {
        OfficePreviewService.UnsupportedTypeException ex = assertThrows(OfficePreviewService.UnsupportedTypeException.class,
                () -> service.ensure(file("notes.txt", 10L, "abc")));
        assertEquals(415, ex.getStatus());
        verifyNoInteractions(converter, objectStorage);
    }

    @Test
    @DisplayName("源文件超 max-source-bytes → TooLargeException(413)，不调用转换")
    void tooLargeSourceThrows413() {
        when(objectStorage.statObject(anyString())).thenThrow(new RuntimeException("not found"));
        OfficePreviewService.TooLargeException ex = assertThrows(OfficePreviewService.TooLargeException.class,
                () -> service.ensure(file("big.docx", 200L * 1024 * 1024, "abc")));
        assertEquals(413, ex.getStatus());
        verify(converter, never()).convert(anyString(), any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("缓存命中：previews/{hash}.pdf 已存在 → 直接返回 key/size，不转换")
    void cacheHitSkipsConversion() {
        when(objectStorage.statObject("previews/2/abc.pdf")).thenReturn(new ObjectStat(321L, "application/pdf", "etag"));
        OfficePreviewService.CachedPdf pdf = service.ensure(file("报告.docx", 1024L, "ABC"));
        assertEquals("previews/2/abc.pdf", pdf.key());
        assertEquals(321L, pdf.size());
        verifyNoInteractions(converter);
    }

    @Test
    @DisplayName("未命中：转换 → 写 previews/{hash}.pdf（PDF 魔数校验）→ 返回大小")
    void convertsAndCachesPdf() {
        when(objectStorage.statObject(anyString())).thenThrow(new RuntimeException("not found"));
        when(converter.convert(eq("http://gotenberg:3000"), any(), eq("报告.docx"), any(InputStream.class), eq(1024L)))
                .thenReturn(new ByteArrayInputStream(PDF));
        AtomicReference<byte[]> storedObject = new AtomicReference<>();
        doAnswer(inv -> {
            try (InputStream in = inv.getArgument(1)) {
                storedObject.set(in.readAllBytes());
            }
            return null;
        }).when(objectStorage).putObject(eq("previews/2/abc.pdf"), any(InputStream.class), eq((long) PDF.length), eq("application/pdf"));

        OfficePreviewService.CachedPdf pdf = service.ensure(file("报告.docx", 1024L, "ABC"));

        assertEquals("previews/2/abc.pdf", pdf.key());
        assertEquals(PDF.length, pdf.size());
        assertArrayEquals(PDF, storedObject.get(), "写缓存的字节应与转换输出一致");
    }

    @Test
    @DisplayName("表格类文件（xlsx）转换时传 singlePageSheets=true：避免宽表/多工作表按打印区域被截列")
    void spreadsheetEnablesSinglePageSheets() {
        when(objectStorage.statObject(anyString())).thenThrow(new RuntimeException("not found"));
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(converter.convert(eq("http://gotenberg:3000"), any(), eq("报表.xlsx"),
                any(InputStream.class), eq(1024L), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(5));
                    return new ByteArrayInputStream(PDF);
                });

        service.ensure(file("报表.xlsx", 1024L, "abc"));

        assertEquals(java.util.Map.of("singlePageSheets", "true"), captured.get(),
                "xlsx 预览应带 singlePageSheets=true，否则宽表会被打印区域截列");
    }

    @Test
    @DisplayName("第二次请求命中缓存：只转换一次")
    void secondCallUsesCache() {
        when(objectStorage.statObject(anyString()))
                .thenThrow(new RuntimeException("not found"))
                .thenThrow(new RuntimeException("not found"))
                .thenReturn(new ObjectStat(PDF.length, "application/pdf", "etag"));
        when(converter.convert(anyString(), any(), anyString(), any(InputStream.class), anyLong()))
                .thenReturn(new ByteArrayInputStream(PDF));

        service.ensure(file("报告.docx", 1024L, "abc"));
        OfficePreviewService.CachedPdf second = service.ensure(file("报告.docx", 1024L, "abc"));

        assertEquals(PDF.length, second.size());
        verify(converter, times(1)).convert(anyString(), any(), anyString(), any(InputStream.class), anyLong());
    }

    @Test
    @DisplayName("坏文档 → BadDocumentException(415) 且负缓存 10 分钟（不重复转换）")
    void badDocumentIsNegativeCached() {
        when(objectStorage.statObject(anyString())).thenThrow(new RuntimeException("not found"));
        when(converter.convert(anyString(), any(), anyString(), any(InputStream.class), anyLong()))
                .thenThrow(new OfficePdfConverter.BadDocumentException("gotenberg rejected"));

        assertThrows(OfficePreviewService.BadDocumentException.class, () -> service.ensure(file("bad.docx", 10L, "abc")));
        assertThrows(OfficePreviewService.BadDocumentException.class, () -> service.ensure(file("bad.docx", 10L, "abc")));
        verify(converter, times(1)).convert(anyString(), any(), anyString(), any(InputStream.class), anyLong());
    }

    @Test
    @DisplayName("转换服务不可用 → UnavailableException(503) 且不缓存（下次仍重试）")
    void unavailableIsNotCached() {
        when(objectStorage.statObject(anyString())).thenThrow(new RuntimeException("not found"));
        when(converter.convert(anyString(), any(), anyString(), any(InputStream.class), anyLong()))
                .thenThrow(new OfficePdfConverter.ConversionUnavailableException("down"));

        assertThrows(OfficePreviewService.UnavailableException.class, () -> service.ensure(file("a.docx", 10L, "abc")));
        assertThrows(OfficePreviewService.UnavailableException.class, () -> service.ensure(file("a.docx", 10L, "abc")));
        verify(converter, times(2)).convert(anyString(), any(), anyString(), any(InputStream.class), anyLong());
    }

    @Test
    @DisplayName("转换返回非 PDF（例如 HTML 错误页）→ 415 并负缓存，不写入缓存")
    void nonPdfResponseRejected() {
        when(objectStorage.statObject(anyString())).thenThrow(new RuntimeException("not found"));
        when(converter.convert(anyString(), any(), anyString(), any(InputStream.class), anyLong()))
                .thenReturn(new ByteArrayInputStream("<html>error</html>".getBytes(StandardCharsets.UTF_8)));

        assertThrows(OfficePreviewService.BadDocumentException.class, () -> service.ensure(file("a.docx", 10L, "abc")));
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("Range 读取：全量走 getObject(key)，部分走 getObject(key,offset,length)")
    void openRangeDelegatesToStorage() {
        InputStream full = new ByteArrayInputStream(new byte[]{1});
        InputStream partial = new ByteArrayInputStream(new byte[]{2});
        when(objectStorage.getObject("previews/2/abc.pdf")).thenReturn(full);
        when(objectStorage.getObject("previews/2/abc.pdf", 100L, 50L)).thenReturn(partial);

        assertSame(full, service.openRange("previews/2/abc.pdf", 1000L, 0L, 1000L));
        assertSame(partial, service.openRange("previews/2/abc.pdf", 1000L, 100L, 50L));
        verify(objectStorage).getObject("previews/2/abc.pdf");
        verify(objectStorage).getObject("previews/2/abc.pdf", 100L, 50L);
    }

    @Test
    @DisplayName("无 fileHash 的历史文件：缓存键回退为 id-size-createdAt；有 hash 时小写化")
    void cacheKeyFallbackAndNormalization() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 23, 10, 0);
        WorkspaceFile noHash = file("old.docx", 2048L, null);
        noHash.setId(9L);
        noHash.setCreatedAt(created);
        long millis = created.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        assertEquals("previews/2/9-2048-" + millis + ".pdf", service.cacheKey(noHash));
        assertEquals("previews/2/abc.pdf", service.cacheKey(file("a.docx", 1L, "ABC")));
        assertTrue(OfficePreviewService.supportsFileName("A.DOCX"));
        assertTrue(OfficePreviewService.supportsFileName("sheet.csv"));
        assertFalse(OfficePreviewService.supportsFileName("pic.png"));
        assertFalse(OfficePreviewService.supportsFileName("noext"));
        assertFalse(OfficePreviewService.supportsFileName(null));
    }

    @Test
    @DisplayName("缓存版本升级：配置值进缓存键前缀，同一文件旧 PDF 被绕过（task-161）")
    void cacheVersionChangesKeyPrefix() {
        when(configService.get(eq("office.preview.cache_version"), eq("2"))).thenReturn("3-cjk");
        assertEquals("previews/3-cjk/abc.pdf", service.cacheKey(file("a.docx", 1L, "ABC")));
    }

    @Test
    @DisplayName("缓存版本清洗：路径穿越/空格被替换，空值回退默认 2")
    void cacheVersionSanitized() {
        when(configService.get(eq("office.preview.cache_version"), eq("2"))).thenReturn("../v3 x");
        String key = service.cacheKey(file("a.docx", 1L, "ABC"));
        assertTrue(key.startsWith("previews/"), key);
        assertFalse(key.contains(".."), key);
        assertFalse(key.contains(" "), key);

        when(configService.get(eq("office.preview.cache_version"), eq("2"))).thenReturn("   ");
        assertEquals("previews/2/abc.pdf", service.cacheKey(file("a.docx", 1L, "ABC")));
    }

    private static void assertArrayEqualsIgnoringIo(InputStream stream, byte[] expected) {
        try {
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected, stream.readAllBytes());
            assertEquals(-1, stream.read(), "流读完后应返回 EOF");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
