package com.bond.transfer.preview;

import com.bond.transfer.controller.PersonalFileController;
import com.bond.transfer.controller.WorkspaceController;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceInvitationService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task-21：预览接口参数错误的状态码契约。
 *
 * <p>缺 token / 畸形 token → 404（与令牌无效语义一致）；畸形 w → 由 {@link ThumbnailService#resolveWidth(String)}
 * 回退 320（见 PreviewUnitTest），接口本身正常 200/204，不再 500。</p>
 */
@ExtendWith(MockitoExtension.class)
class PreviewErrorHandlingTest {

    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PreviewService previewService;

    private PersonalFileController personalController;
    private WorkspaceController workspaceController;

    @BeforeEach
    void setUp() {
        personalController = new PersonalFileController(personalSpaceService, workspaceService, previewService,
                mock(com.bond.transfer.service.FileSearchService.class));
        workspaceController = new WorkspaceController(workspaceService,
                mock(WorkspaceInvitationService.class), previewService,
                mock(com.bond.transfer.service.FileSearchService.class),
                mock(com.bond.transfer.acl.WorkspaceAclService.class));
    }

    @Test
    @DisplayName("个人空间 thumbnail 缺 token → 404（不是 500）")
    void personalThumbnailMissingTokenReturns404() {
        assertEquals(HttpStatus.NOT_FOUND, personalController.thumbnail(1L, "abc", null, null).getStatusCode());
    }

    @Test
    @DisplayName("个人空间 content 缺 token → 404（不是 500）")
    void personalContentMissingTokenReturns404() {
        assertEquals(HttpStatus.NOT_FOUND, personalController.content(1L, null, null).getStatusCode());
    }

    @Test
    @DisplayName("工作区 thumbnail/content 缺 token → 404")
    void workspaceMissingTokenReturns404() {
        assertEquals(HttpStatus.NOT_FOUND, workspaceController.thumbnail(9L, 1L, "320", null, null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, workspaceController.content(9L, 1L, " ", null).getStatusCode());
    }

    @Test
    @DisplayName("畸形 token → 404（PreviewService 抛无效令牌异常）")
    void malformedTokenReturns404() {
        when(previewService.personalThumbnail("bad-token", 1L, "320"))
                .thenThrow(new PreviewService.InvalidPreviewTokenException());
        assertEquals(HttpStatus.NOT_FOUND, personalController.thumbnail(1L, "320", "bad-token", null).getStatusCode());

        when(previewService.openPersonalContent("bad-token", 1L, null))
                .thenThrow(new PreviewService.InvalidPreviewTokenException());
        assertEquals(HttpStatus.NOT_FOUND, personalController.content(1L, "bad-token", null).getStatusCode());
    }

    @Test
    @DisplayName("w=abc + 有效 token：透传字符串宽度（由 service 回退 320），响应 200 image/jpeg")
    void invalidWidthStillReturnsThumbnail() {
        byte[] jpeg = new byte[]{1, 2, 3};
        when(previewService.personalThumbnail("ok-token", 1L, "abc"))
                .thenReturn(new PreviewService.Thumbnail(jpeg, "\"etag-1\""));

        var response = personalController.thumbnail(1L, "abc", "ok-token", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        assertEquals(3, response.getBody().length);
        assertEquals("\"etag-1\"", response.getHeaders().getETag());
    }

    @Test
    @DisplayName("缩略图软限流：429 + Retry-After，响应体保留业务错误码（客户端降级图标，不 500/204）")
    void thumbnailRateLimitMaps429() {
        when(previewService.personalThumbnail("busy-token", 1L, "320"))
                .thenThrow(new PreviewService.ThumbnailRateLimitedException(42L));

        var response = personalController.thumbnail(1L, "320", "busy-token", null);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("42", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNotNull(response.getBody());
        assertTrue(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"code\":429"));
    }

    @Test
    @DisplayName("工作区缩略图同样映射 429 + Retry-After")
    void workspaceThumbnailRateLimitMaps429() {
        when(previewService.workspaceThumbnail("busy-token", 9L, 1L, "320"))
                .thenThrow(new PreviewService.ThumbnailRateLimitedException(7L));

        var response = workspaceController.thumbnail(9L, 1L, "320", "busy-token", null);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("7", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
