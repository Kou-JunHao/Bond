package com.bond.transfer.service;

import com.bond.common.dto.SoftwarePackageDTO;
import com.bond.common.dto.SoftwareReleaseDTO;
import com.bond.common.entity.SoftwarePackage;
import com.bond.common.entity.SoftwareRelease;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.SoftwarePackageMapper;
import com.bond.transfer.mapper.SoftwareReleaseMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SoftwareServiceTest {

    private static final String SHA256_HELLO =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Mock
    private SoftwarePackageMapper packageMapper;
    @Mock
    private SoftwareReleaseMapper releaseMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private SoftwareService softwareService;

    private static SoftwarePackage pkg(Long id, String code) {
        SoftwarePackage p = new SoftwarePackage();
        p.setId(id);
        p.setCode(code);
        p.setName("Bond 客户端");
        p.setDescription("desktop");
        p.setPlatform("windows");
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    private static SoftwareRelease release(Long id, Long pkgId, boolean published) {
        SoftwareRelease r = new SoftwareRelease();
        r.setId(id);
        r.setPackageId(pkgId);
        r.setVersion("1.4.0");
        r.setChannel("stable");
        r.setFileName("Bond-1.4.0.exe");
        r.setFileSize(10L);
        r.setSha256(SHA256_HELLO);
        r.setMinioPath("software/bond-pan/1.4.0/Bond-1.4.0.exe");
        r.setPublished(published);
        r.setDownloadCount(0L);
        r.setCreatedAt(OffsetDateTime.now());
        return r;
    }

    // ------------------------------------------------------------- 客户端列表

    @Test
    @DisplayName("包列表合并已发布版本统计，不出现未发布包")
    void listPackagesMergesPublishedStats() {
        when(packageMapper.selectPublished()).thenReturn(List.of(pkg(1L, "bond-pan"), pkg(2L, "bond-cli")));
        when(releaseMapper.statsByPackage()).thenReturn(List.of(
                Map.of("package_id", 1L, "total", 2L, "latest_version", "1.4.0")));

        List<SoftwarePackageDTO> result = softwareService.listPackages();

        assertEquals(2, result.size());
        assertEquals("bond-pan", result.get(0).code());
        assertEquals("1.4.0", result.get(0).latestVersion());
        assertEquals(2L, result.get(0).releaseCount());
        assertNull(result.get(1).latestVersion());
        assertEquals(0L, result.get(1).releaseCount());
    }

    @Test
    @DisplayName("包不存在 -> 404")
    void listReleasesUnknownPackage404() {
        when(packageMapper.selectByCode("nope")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.listReleases("nope", null, 50));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("版本列表：渠道归一、limit 截断、只返回已发布（SQL 已过滤）")
    void listReleasesNormalizesAndClamps() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(releaseMapper.selectPublishedByPackage(eq(1L), eq("beta"), eq(200)))
                .thenReturn(List.of(release(9L, 1L, true)));

        List<SoftwareReleaseDTO> result = softwareService.listReleases("bond-pan", "BETA", 999);

        assertEquals(1, result.size());
        assertEquals("bond-pan", result.get(0).packageCode());
        assertEquals(9L, result.get(0).id());
    }

    // ------------------------------------------------------------- 管理端列表（task-92）

    @Test
    @DisplayName("管理端列表：包含未发布（publishedAt=null）与已发布；顺序按 SQL 输出透传")
    void adminListReleasesIncludesUnpublished() {
        SoftwareRelease unpublished = release(9L, 1L, false);
        unpublished.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        SoftwareRelease published = release(8L, 1L, true);
        published.setCreatedAt(OffsetDateTime.now().minusHours(1));
        published.setPublishedAt(OffsetDateTime.now().minusMinutes(50));
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(releaseMapper.selectAllByPackage(1L, 200)).thenReturn(List.of(unpublished, published));

        List<SoftwareReleaseDTO> result = softwareService.adminListReleases("bond-pan", 200);

        assertEquals(2, result.size());
        assertEquals(9L, result.get(0).id());
        assertNull(result.get(0).publishedAt());
        assertFalse(result.get(0).published());
        assertEquals("bond-pan", result.get(0).packageCode());
        assertEquals(8L, result.get(1).id());
        assertNotNull(result.get(1).publishedAt());
        assertTrue(result.get(1).published());
    }

    @Test
    @DisplayName("管理端列表：包不存在 -> 404")
    void adminListReleasesUnknownPackage404() {
        when(packageMapper.selectByCode("nope")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.adminListReleases("nope", 200));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("管理端列表：limit 截断到上限（999 -> 200；0 -> 默认 200）")
    void adminListReleasesClampsLimit() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(releaseMapper.selectAllByPackage(eq(1L), eq(200))).thenReturn(List.of());

        softwareService.adminListReleases("bond-pan", 999);
        softwareService.adminListReleases("bond-pan", 0);

        verify(releaseMapper, org.mockito.Mockito.times(2)).selectAllByPackage(1L, 200);
    }

    @Test
    @DisplayName("未发布版本详情 -> 404")
    void getReleaseUnpublished404() {
        when(releaseMapper.selectById(3L)).thenReturn(release(3L, 1L, false));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.getRelease(3L));
        assertEquals(404, ex.getCode());
    }

    // ------------------------------------------------------------- 下载

    @Test
    @DisplayName("完整下载：返回全长流并累加 download_count")
    void openDownloadFull() {
        when(releaseMapper.selectById(1L)).thenReturn(release(1L, 1L, true));
        when(objectStorage.getObject("software/bond-pan/1.4.0/Bond-1.4.0.exe"))
                .thenReturn(new ByteArrayInputStream(new byte[10]));

        SoftwareService.DownloadStream stream = softwareService.openDownload(1L, null);

        assertFalse(stream.partial());
        assertEquals(10L, stream.fileSize());
        assertEquals(10L, stream.length());
        verify(objectStorage).getObject("software/bond-pan/1.4.0/Bond-1.4.0.exe");
        verify(releaseMapper).incrementDownloadCount(1L);
    }

    @Test
    @DisplayName("Range 下载：只取请求区间，同样计数")
    void openDownloadRange() {
        when(releaseMapper.selectById(1L)).thenReturn(release(1L, 1L, true));
        when(objectStorage.getObject("software/bond-pan/1.4.0/Bond-1.4.0.exe", 0L, 4L))
                .thenReturn(new ByteArrayInputStream(new byte[4]));

        SoftwareService.DownloadStream stream = softwareService.openDownload(1L, "bytes=0-3");

        assertTrue(stream.partial());
        assertEquals(0L, stream.offset());
        assertEquals(4L, stream.length());
        verify(objectStorage).getObject("software/bond-pan/1.4.0/Bond-1.4.0.exe", 0L, 4L);
        verify(releaseMapper).incrementDownloadCount(1L);
    }

    @Test
    @DisplayName("Range 越界 -> RangeNotSatisfiableException（带总长）")
    void openDownloadUnsatisfiableRange() {
        when(releaseMapper.selectById(1L)).thenReturn(release(1L, 1L, true));
        SoftwareService.RangeNotSatisfiableException ex = assertThrows(
                SoftwareService.RangeNotSatisfiableException.class,
                () -> softwareService.openDownload(1L, "bytes=20-30"));
        assertEquals(10L, ex.totalSize());
        verify(releaseMapper, never()).incrementDownloadCount(anyLong());
    }

    @Test
    @DisplayName("对象存储读取失败 -> 503")
    void openDownloadStorageFailure503() {
        when(releaseMapper.selectById(1L)).thenReturn(release(1L, 1L, true));
        when(objectStorage.getObject("software/bond-pan/1.4.0/Bond-1.4.0.exe"))
                .thenThrow(new RuntimeException("s3 down"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.openDownload(1L, null));
        assertEquals(503, ex.getCode());
    }

    @Test
    @DisplayName("未发布版本下载 -> 404")
    void openDownloadUnpublished404() {
        when(releaseMapper.selectById(1L)).thenReturn(release(1L, 1L, false));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.openDownload(1L, null));
        assertEquals(404, ex.getCode());
        verify(objectStorage, never()).getObject(anyString());
    }

    // ------------------------------------------------------------- 上传

    @Test
    @DisplayName("上传：流式计算 SHA-256 与大小，写对象存储并落库（默认未发布）")
    void uploadReleaseComputesSha256() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(configService.getLong(SoftwareService.MAX_RELEASE_BYTES_KEY,
                SoftwareService.DEFAULT_MAX_RELEASE_BYTES)).thenReturn(1024L);
        when(releaseMapper.selectByVersion(1L, "1.5.0", "stable")).thenReturn(null);
        when(releaseMapper.insert(any(SoftwareRelease.class))).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile("file", "Bond-1.5.0.exe",
                "application/octet-stream", "hello".getBytes());
        SoftwareReleaseDTO dto = softwareService.uploadRelease("Bond-PAN", "1.5.0", null,
                "release notes", null, file, 7L);

        ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(objectStorage).putObject(eq("software/bond-pan/1.5.0/Bond-1.5.0.exe"),
                any(InputStream.class), sizeCaptor.capture(), eq("application/octet-stream"));
        assertEquals(5L, sizeCaptor.getValue());
        assertEquals(SHA256_HELLO, dto.sha256());
        assertEquals(5L, dto.fileSize());
        assertEquals("stable", dto.channel());
        assertEquals("bond-pan", dto.packageCode());
        verify(releaseMapper).insert(any(SoftwareRelease.class));
    }

    @Test
    @DisplayName("上传：sha256 不一致 -> 400")
    void uploadReleaseShaMismatch400() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(configService.getLong(SoftwareService.MAX_RELEASE_BYTES_KEY,
                SoftwareService.DEFAULT_MAX_RELEASE_BYTES)).thenReturn(1024L);
        when(releaseMapper.selectByVersion(1L, "1.5.0", "stable")).thenReturn(null);
        MockMultipartFile file = new MockMultipartFile("file", "a.exe",
                "application/octet-stream", "hello".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.uploadRelease("bond-pan", "1.5.0", null, null,
                        "0".repeat(64), file, 7L));
        assertEquals(400, ex.getCode());
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class),
                anyLong(), anyString());
    }

    @Test
    @DisplayName("上传：无文件 -> 400")
    void uploadReleaseMissingFile400() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.uploadRelease("bond-pan", "1.5.0", null, null, null, null, 7L));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("上传：超过大小上限 -> 413")
    void uploadReleaseTooLarge413() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(configService.getLong(SoftwareService.MAX_RELEASE_BYTES_KEY,
                SoftwareService.DEFAULT_MAX_RELEASE_BYTES)).thenReturn(4L);
        when(releaseMapper.selectByVersion(1L, "1.5.0", "stable")).thenReturn(null);
        MockMultipartFile file = new MockMultipartFile("file", "a.exe",
                "application/octet-stream", "hello".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.uploadRelease("bond-pan", "1.5.0", null, null, null, file, 7L));
        assertEquals(413, ex.getCode());
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class),
                anyLong(), anyString());
    }

    @Test
    @DisplayName("上传：同包同版本重复 -> 409")
    void uploadReleaseDuplicate409() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(configService.getLong(SoftwareService.MAX_RELEASE_BYTES_KEY,
                SoftwareService.DEFAULT_MAX_RELEASE_BYTES)).thenReturn(1024L);
        when(releaseMapper.selectByVersion(1L, "1.5.0", "stable"))
                .thenReturn(release(2L, 1L, true));
        MockMultipartFile file = new MockMultipartFile("file", "a.exe",
                "application/octet-stream", "hello".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.uploadRelease("bond-pan", "1.5.0", null, null, null, file, 7L));
        assertEquals(409, ex.getCode());
    }

    // ------------------------------------------------------------- 管理端

    @Test
    @DisplayName("发布/取消发布：状态与 publishedAt 同步变更")
    void publishAndUnpublish() {
        SoftwareRelease r = release(1L, 1L, false);
        when(releaseMapper.selectById(1L)).thenReturn(r);
        when(packageMapper.selectById(1L)).thenReturn(pkg(1L, "bond-pan"));

        SoftwareReleaseDTO published = softwareService.publishRelease(1L);
        assertTrue(r.getPublished());
        assertEquals(true, published.publishedAt() != null);

        SoftwareReleaseDTO unpublished = softwareService.unpublishRelease(1L);
        assertFalse(r.getPublished());
        assertNull(unpublished.publishedAt());
    }

    @Test
    @DisplayName("删除版本：删行并尽力删除对象存储")
    void deleteRelease() {
        when(releaseMapper.selectById(5L)).thenReturn(release(5L, 1L, true));
        softwareService.deleteRelease(5L);
        verify(releaseMapper).deleteById(5L);
        verify(objectStorage).deleteObject("software/bond-pan/1.4.0/Bond-1.4.0.exe");
    }

    @Test
    @DisplayName("删除不存在的版本 -> 404")
    void deleteRelease404() {
        when(releaseMapper.selectById(5L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.deleteRelease(5L));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("创建包：code 归一化、平台校验、重复 409")
    void createPackageNormalizesAndRejectsDuplicate() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(null);
        when(packageMapper.insert(any(SoftwarePackage.class))).thenReturn(1);

        SoftwarePackageDTO dto = softwareService.createPackage(" Bond-PAN ", "Bond 客户端",
                " desktop ", "Windows", 7L);
        assertEquals("bond-pan", dto.code());
        assertEquals("windows", dto.platform());
        assertEquals("desktop", dto.description());

        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.createPackage("bond-pan", "x", null, null, 7L));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("创建包：非法 code/平台 -> 400")
    void createPackageRejectsInvalidInput() {
        BusinessException badCode = assertThrows(BusinessException.class,
                () -> softwareService.createPackage("Bond Pan", "x", null, null, 7L));
        assertEquals(400, badCode.getCode());

        BusinessException badPlatform = assertThrows(BusinessException.class,
                () -> softwareService.createPackage("bond-pan", "x", null, "solaris", 7L));
        assertEquals(400, badPlatform.getCode());
    }

    // ------------------------------------------------------------- 工具方法

    @Test
    @DisplayName("文件名清洗：去路径与控制字符、空名回退")
    void sanitizeFileName() {
        assertEquals("a.exe", SoftwareService.sanitizeFileName("../../etc/a.exe"));
        assertEquals("a.exe", SoftwareService.sanitizeFileName("C:\\temp\\a.exe"));
        assertEquals("release.bin", SoftwareService.sanitizeFileName(".."));
        assertEquals("a.exe", SoftwareService.sanitizeFileName("a.exe\u0000"));
    }

    // ------------------------------------------------------------- 包改名/删除（task-85）

    @Test
    @DisplayName("改名：字段按传入更新并返回 DTO；code 不可改")
    void updatePackageSuccess() {
        SoftwarePackage existing = pkg(1L, "bond-pan");
        when(packageMapper.selectByCode("bond-pan")).thenReturn(existing);
        when(releaseMapper.statsByPackageAll()).thenReturn(List.<Map<String, Object>>of());

        SoftwarePackageDTO dto = softwareService.updatePackage("bond-pan", " Bond 桌面端 ",
                " new-desc ", "MacOS");

        assertEquals("bond-pan", dto.code());
        assertEquals("Bond 桌面端", dto.name());
        assertEquals("new-desc", dto.description());
        assertEquals("macos", dto.platform());
        // task-106/107：实现用 UpdateWrapper 显式 set（而非 updateById）以支持“清空”字段
        verify(packageMapper).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
        assertEquals("new-desc", existing.getDescription());
        assertEquals("macos", existing.getPlatform());
    }

    @Test
    @DisplayName("改名：空 body（所有字段 null）/ 空 name -> 400，且不落库")
    void updatePackageRejectsEmptyAndBlankName() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));

        BusinessException empty = assertThrows(BusinessException.class,
                () -> softwareService.updatePackage("bond-pan", null, null, null));
        assertEquals(400, empty.getCode());

        BusinessException blank = assertThrows(BusinessException.class,
                () -> softwareService.updatePackage("bond-pan", "   ", null, null));
        assertEquals(400, blank.getCode());

        verify(packageMapper, never()).updateById(any(SoftwarePackage.class));
    }

    @Test
    @DisplayName("改名：包不存在 -> 404")
    void updatePackageUnknown404() {
        when(packageMapper.selectByCode("ghost")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.updatePackage("ghost", "x", null, null));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("删包：仍有 release（含未发布）-> 409 且不删行")
    void deletePackageWithReleases409() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(releaseMapper.selectCount(any())).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.deletePackage("bond-pan"));
        assertEquals(409, ex.getCode());
        verify(packageMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删包：空包 204 语义（直接删行），随后再查 404")
    void deleteEmptyPackageThenGet404() {
        when(packageMapper.selectByCode("bond-pan")).thenReturn(pkg(1L, "bond-pan"));
        when(releaseMapper.selectCount(any())).thenReturn(0L);

        softwareService.deletePackage("bond-pan");
        verify(packageMapper).deleteById(1L);

        when(packageMapper.selectByCode("bond-pan")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.updatePackage("bond-pan", "x", null, null));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("删包：包不存在 -> 404")
    void deletePackageUnknown404() {
        when(packageMapper.selectByCode("ghost")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> softwareService.deletePackage("ghost"));
        assertEquals(404, ex.getCode());
    }
}
