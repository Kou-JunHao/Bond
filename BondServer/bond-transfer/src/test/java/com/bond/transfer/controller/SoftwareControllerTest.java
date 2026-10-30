package com.bond.transfer.controller;

import com.bond.common.dto.SoftwarePackageDTO;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.service.SoftwareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户端软件分发接口契约（task-81）：/api/software/**。
 */
class SoftwareControllerTest {

    private SoftwareService softwareService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        softwareService = mock(SoftwareService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SoftwareController(softwareService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static SoftwareService.DownloadStream stream(long total, long offset, long length,
                                                         boolean partial) {
        return new SoftwareService.DownloadStream(new ByteArrayInputStream(new byte[(int) length]),
                total, offset, length, partial, "Bond-1.4.0.exe");
    }

    @Test
    @DisplayName("GET /api/software/packages -> 200 列表")
    void listPackages() throws Exception {
        when(softwareService.listPackages()).thenReturn(List.of(
                new SoftwarePackageDTO("bond-pan", "Bond 客户端", "desktop", "windows",
                        "1.4.0", 2L, OffsetDateTime.now())));

        mockMvc.perform(get("/api/software/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].code").value("bond-pan"))
                .andExpect(jsonPath("$.data[0].latestVersion").value("1.4.0"));
    }

    @Test
    @DisplayName("GET /api/software/releases/{id} -> 200")
    void getRelease() throws Exception {
        when(softwareService.getRelease(9L)).thenReturn(new com.bond.common.dto.SoftwareReleaseDTO(
                9L, "bond-pan", "1.4.0", "stable", "Bond-1.4.0.exe", 10L,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                "notes", 3L, OffsetDateTime.now(), OffsetDateTime.now(), true));

        mockMvc.perform(get("/api/software/releases/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.packageCode").value("bond-pan"))
                .andExpect(jsonPath("$.data.minioPath").doesNotExist());
    }

    @Test
    @DisplayName("未知包 -> 404 业务错误")
    void unknownPackage404() throws Exception {
        when(softwareService.listReleases(eq("nope"), isNull(), anyInt())).thenThrow(
                new BusinessException(404, "包不存在"));

        mockMvc.perform(get("/api/software/packages/nope/releases"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("完整下载 -> 200 + Content-Length/Accept-Ranges/Content-Disposition")
    void downloadFull() throws Exception {
        when(softwareService.openDownload(eq(1L), isNull())).thenReturn(stream(10, 0, 10, false));

        mockMvc.perform(get("/api/software/releases/1/download"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 10))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Disposition", containsString("Bond-1.4.0.exe")))
                .andExpect(content().contentType("application/octet-stream"));
    }

    @Test
    @DisplayName("Range 下载 -> 206 + Content-Range")
    void downloadRange() throws Exception {
        when(softwareService.openDownload(eq(1L), eq("bytes=2-5"))).thenReturn(stream(10, 2, 4, true));

        mockMvc.perform(get("/api/software/releases/1/download").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(header().longValue("Content-Length", 4));
    }

    @Test
    @DisplayName("Range 越界 -> 416 + Content-Range: bytes * / total")
    void downloadUnsatisfiable416() throws Exception {
        when(softwareService.openDownload(eq(1L), eq("bytes=20-30")))
                .thenThrow(new SoftwareService.RangeNotSatisfiableException(10));

        mockMvc.perform(get("/api/software/releases/1/download").header("Range", "bytes=20-30"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */10"));
    }

    @Test
    @DisplayName("未发布/不存在版本下载 -> 404")
    void downloadMissing404() throws Exception {
        when(softwareService.openDownload(eq(2L), isNull()))
                .thenThrow(new BusinessException(404, "版本不存在或未发布"));

        mockMvc.perform(get("/api/software/releases/2/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
