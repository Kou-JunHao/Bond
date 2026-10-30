package com.bond.transfer.controller;

import com.bond.common.dto.SoftwarePackageDTO;
import com.bond.common.dto.SoftwareReleaseDTO;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.interceptor.AdminInterceptor;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.SoftwareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端软件分发接口契约（task-81）：/api/admin/software/**。
 *
 * <p>验证 AdminInterceptor 的管理员守卫（401/403）与上传/发布/删除接口。</p>
 */
class AdminSoftwareControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final long NORMAL_ID = 2L;

    private SoftwareService softwareService;
    private MockMvc mockMvc;

    private static User user(long id, boolean admin) {
        User user = new User();
        user.setId(id);
        user.setIsAdmin(admin);
        return user;
    }

    private static SoftwareReleaseDTO releaseDTO() {
        return new SoftwareReleaseDTO(9L, "bond-pan", "1.4.0", "stable", "Bond-1.4.0.exe",
                10L, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                "notes", 0L, null, OffsetDateTime.now(), true);
    }

    @BeforeEach
    void setUp() {
        softwareService = mock(SoftwareService.class);
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, true));
        when(userMapper.selectById(NORMAL_ID)).thenReturn(user(NORMAL_ID, false));

        mockMvc = MockMvcBuilders.standaloneSetup(new AdminSoftwareController(softwareService))
                .addInterceptors(new AdminInterceptor(userMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("非管理员 -> 403")
    void nonAdmin403() throws Exception {
        mockMvc.perform(post("/api/admin/software/releases/9/publish")
                        .header("X-User-Id", NORMAL_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("缺少身份头 -> 401")
    void missingUserHeader401() throws Exception {
        mockMvc.perform(post("/api/admin/software/releases/9/publish"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /packages -> 200 创建包")
    void createPackage() throws Exception {
        when(softwareService.createPackage("bond-pan", "Bond 客户端", "desktop", "windows", ADMIN_ID))
                .thenReturn(new SoftwarePackageDTO("bond-pan", "Bond 客户端", "desktop",
                        "windows", null, 0L, OffsetDateTime.now()));

        mockMvc.perform(post("/api/admin/software/packages")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType("application/json")
                        .content("{\"code\":\"bond-pan\",\"name\":\"Bond 客户端\","
                                + "\"description\":\"desktop\",\"platform\":\"windows\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("bond-pan"));
    }

    @Test
    @DisplayName("POST /releases multipart 上传 -> 200")
    void uploadRelease() throws Exception {
        when(softwareService.uploadRelease(eq("bond-pan"), eq("1.4.0"), eq("stable"),
                isNull(), isNull(), any(MultipartFile.class), eq(ADMIN_ID)))
                .thenReturn(releaseDTO());

        mockMvc.perform(multipart("/api/admin/software/releases")
                        .file(new MockMultipartFile("file", "Bond-1.4.0.exe",
                                "application/octet-stream", "hello".getBytes()))
                        .param("packageCode", "bond-pan")
                        .param("version", "1.4.0")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.4.0"))
                .andExpect(jsonPath("$.data.sha256").value(
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
    }

    @Test
    @DisplayName("POST /releases 缺文件 -> 400（由服务层校验）")
    void uploadReleaseMissingFile400() throws Exception {
        when(softwareService.uploadRelease(eq("bond-pan"), eq("1.4.0"), eq("stable"),
                isNull(), isNull(), isNull(), eq(ADMIN_ID)))
                .thenThrow(new BusinessException(400, "缺少安装包文件"));

        mockMvc.perform(multipart("/api/admin/software/releases")
                        .param("packageCode", "bond-pan")
                        .param("version", "1.4.0")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST publish/unpublish -> 200")
    void publishAndUnpublish() throws Exception {
        when(softwareService.publishRelease(9L)).thenReturn(releaseDTO());
        when(softwareService.unpublishRelease(9L)).thenReturn(releaseDTO());

        mockMvc.perform(post("/api/admin/software/releases/9/publish")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/software/releases/9/unpublish")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /releases/{id} -> 204")
    void deleteRelease() throws Exception {
        mockMvc.perform(delete("/api/admin/software/releases/9")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isNoContent());
        verify(softwareService).deleteRelease(9L);
    }

    // ------------------------------------------------------------- 包改名/删除（task-85）

    @Test
    @DisplayName("PUT /packages/{code} -> 200 改名/改描述/换平台")
    void updatePackage() throws Exception {
        when(softwareService.updatePackage("bond-pan", "Bond 桌面端", "new", "macos"))
                .thenReturn(new SoftwarePackageDTO("bond-pan", "Bond 桌面端", "new",
                        "macos", null, 0L, OffsetDateTime.now()));

        mockMvc.perform(put("/api/admin/software/packages/bond-pan")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Bond 桌面端\",\"description\":\"new\",\"platform\":\"macos\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Bond 桌面端"))
                .andExpect(jsonPath("$.data.platform").value("macos"));
    }

    @Test
    @DisplayName("PUT /packages/{code}：空 body -> 400（服务层报错）")
    void updatePackageEmpty400() throws Exception {
        when(softwareService.updatePackage(eq("bond-pan"), isNull(), isNull(), isNull()))
                .thenThrow(new BusinessException(400, "没有需要修改的字段"));

        mockMvc.perform(put("/api/admin/software/packages/bond-pan")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("DELETE /packages/{code}：空包 204；仍有 release 409")
    void deletePackageStatuses() throws Exception {
        mockMvc.perform(delete("/api/admin/software/packages/bond-pan")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isNoContent());
        verify(softwareService).deletePackage("bond-pan");

        org.mockito.Mockito.doThrow(new BusinessException(409, "请先删除该包下所有 release"))
                .when(softwareService).deletePackage("bond-pan");
        mockMvc.perform(delete("/api/admin/software/packages/bond-pan")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    // ------------------------------------------------------------- 管理端版本列表（task-92）

    @Test
    @DisplayName("GET /packages/{code}/releases -> 200，含未发布版本（publishedAt=null）")
    void adminListReleasesIncludesUnpublished() throws Exception {
        SoftwareReleaseDTO unpublished = new SoftwareReleaseDTO(9L, "bond-pan", "1.5.0", "stable",
                "Bond-1.5.0.exe", 10L, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                "notes", 0L, null, OffsetDateTime.now(), false);
        SoftwareReleaseDTO published = new SoftwareReleaseDTO(8L, "bond-pan", "1.4.0", "stable",
                "Bond-1.4.0.exe", 10L, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                "notes", 0L, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().minusDays(2), true);
        when(softwareService.adminListReleases("bond-pan", 200))
                .thenReturn(List.of(unpublished, published));

        mockMvc.perform(get("/api/admin/software/packages/bond-pan/releases")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].version").value("1.5.0"))
                .andExpect(jsonPath("$.data[0].publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data[1].publishedAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /packages/{code}/releases?limit=50 -> limit 透传给服务层")
    void adminListReleasesForwardsLimit() throws Exception {
        when(softwareService.adminListReleases("bond-pan", 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/software/packages/bond-pan/releases")
                        .param("limit", "50")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(softwareService).adminListReleases("bond-pan", 50);
    }

    @Test
    @DisplayName("GET /packages/{code}/releases：包不存在 -> 404")
    void adminListReleasesUnknownPackage404() throws Exception {
        when(softwareService.adminListReleases("nope", 200))
                .thenThrow(new BusinessException(404, "软件包不存在"));

        mockMvc.perform(get("/api/admin/software/packages/nope/releases")
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
