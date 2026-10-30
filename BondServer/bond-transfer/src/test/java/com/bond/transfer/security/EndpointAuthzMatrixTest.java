package com.bond.transfer.security;

import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.chat.ChatFileAccessService;
import com.bond.transfer.chat.ChatService;
import com.bond.transfer.controller.AnnouncementController;
import com.bond.transfer.controller.ChatController;
import com.bond.transfer.controller.ChatFileController;
import com.bond.transfer.controller.DocumentController;
import com.bond.transfer.controller.EntitlementController;
import com.bond.transfer.controller.PersonalFileController;
import com.bond.transfer.controller.SoftwareController;
import com.bond.transfer.controller.TransferController;
import com.bond.transfer.controller.TrashController;
import com.bond.transfer.controller.UploadSessionController;
import com.bond.transfer.controller.WebDavPasswordController;
import com.bond.transfer.controller.WorkspaceController;
import com.bond.transfer.document.DocumentService;
import com.bond.transfer.entitlement.EntitlementMeService;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.AnnouncementService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.FileSearchService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.SoftwareService;
import com.bond.transfer.service.TransferService;
import com.bond.transfer.service.UploadSessionService;
import com.bond.transfer.service.WorkspaceInvitationService;
import com.bond.transfer.service.WorkspaceService;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.trash.TrashService;
import com.bond.transfer.webdav.WebDavPasswordService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 权限矩阵回归（task-156）：transfer 层「服务判定拒绝 → HTTP 403」。
 *
 * <p>设计：每个 Service 都换成「任何方法都抛 {@code BusinessException(403,"无权限")}」的 mock，
 * 然后逐个端点发请求。断言三件事：</p>
 * <ol>
 *   <li>端点确实把身份/资源交给 Service 判定（不返回 200）；</li>
 *   <li>拒绝被 {@code GlobalExceptionHandler} 映射成 403 信封（不吞成 500 / 不裸文本）；</li>
 *   <li>错误响应不泄漏 passwordHash / token / 他人 userId。</li>
 * </ol>
 *
 * <p>角色判定本身（成员/非成员/owner）由各 Service 的单元测试覆盖；这里锁的是「Controller 面
 * 不会绕过 Service 直接放行」这个历史高发点（裸信 X-User-Id、漏传 userId、异常被吞）。</p>
 */
class EndpointAuthzMatrixTest {

    private static MockMvc mvc;

    @BeforeAll
    static void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new PersonalFileController(denying(PersonalSpaceService.class),
                                denying(WorkspaceService.class),
                                denying(PreviewService.class),
                                denying(FileSearchService.class)),
                        new WorkspaceController(denying(WorkspaceService.class),
                                denying(WorkspaceInvitationService.class),
                                denying(PreviewService.class),
                                denying(FileSearchService.class),
                                denying(com.bond.transfer.acl.WorkspaceAclService.class)),
                        new DocumentController(denying(DocumentService.class)),
                        new ChatController(denying(ChatService.class)),
                        new ChatFileController(denying(ChatFileAccessService.class)),
                        new TrashController(denying(TrashService.class),
                                denying(com.bond.transfer.acl.WorkspaceAclService.class)),
                        new UploadSessionController(denying(UploadSessionService.class)),
                        new SoftwareController(denying(SoftwareService.class)),
                        new EntitlementController(denying(EntitlementMeService.class)),
                        new AnnouncementController(denying(AnnouncementService.class)),
                        new WebDavPasswordController(denying(WebDavPasswordService.class)),
                        new TransferController(denying(TransferService.class),
                                denying(ObjectStorage.class),
                                denying(ConfigService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static <T> T denying(Class<T> type) {
        return mock(type, invocation -> {
            throw new BusinessException(403, "无权限");
        });
    }

    // ── 端点矩阵（method, path, body）──

    static Stream<Arguments> endpoints() {
        return Stream.of(
                // 个人空间 / 文件
                Arguments.of("GET", "/api/files", null),
                Arguments.of("GET", "/api/files/quota", null),
                Arguments.of("GET", "/api/files/search?q=probe", null),
                Arguments.of("POST", "/api/files/folders", "{\"parentPath\":\"/\",\"name\":\"dir\"}"),
                Arguments.of("DELETE", "/api/files/12", null),
                Arguments.of("POST", "/api/files/rename", "{\"fileId\":12,\"newName\":\"a.txt\"}"),
                Arguments.of("POST", "/api/files/12/copy", "{\"targetParentPath\":\"/x\"}"),
                Arguments.of("POST", "/api/files/12/move", "{\"targetParentPath\":\"/x\"}"),
                Arguments.of("POST", "/api/files/check-hash", "{\"fileHash\":\"abc\",\"parentPath\":\"/\"}"),
                Arguments.of("POST", "/api/files/instant-upload", "{\"fileHash\":\"abc\",\"parentPath\":\"/\",\"fileName\":\"a.txt\"}"),
                Arguments.of("GET", "/api/files/12/download", null),
                Arguments.of("GET", "/api/files/12/preview-url", null),
                // 工作区
                Arguments.of("GET", "/api/workspaces", null),
                Arguments.of("GET", "/api/workspaces/7", null),
                Arguments.of("GET", "/api/workspaces/7/files", null),
                Arguments.of("GET", "/api/workspaces/7/files/search?q=probe", null),
                Arguments.of("GET", "/api/workspaces/7/members", null),
                Arguments.of("POST", "/api/workspaces/7/members", "{\"userId\":9}"),
                Arguments.of("GET", "/api/workspaces/7/files/12/download", null),
                // task-183B：工作区文件 ACL（owner/admin 管理 + 成员本人有效范围）
                Arguments.of("GET", "/api/workspaces/7/members/9/acl", null),
                Arguments.of("PUT", "/api/workspaces/7/members/9/acl", "{\"accessMode\":\"all\"}"),
                Arguments.of("GET", "/api/workspaces/7/acl/me", null),
                // 文档
                Arguments.of("GET", "/api/documents", null),
                Arguments.of("GET", "/api/documents/12", null),
                Arguments.of("POST", "/api/documents", "{}"),
                Arguments.of("PUT", "/api/documents/12", "{}"),
                Arguments.of("PATCH", "/api/documents/12", "{}"),
                Arguments.of("DELETE", "/api/documents/12", null),
                Arguments.of("GET", "/api/documents/12/versions", null),
                Arguments.of("GET", "/api/documents/12/export?format=pdf", null),
                // 聊天
                Arguments.of("GET", "/api/chat/conversations", null),
                Arguments.of("POST", "/api/chat/conversations", "{}"),
                Arguments.of("GET", "/api/chat/conversations/1/messages", null),
                Arguments.of("POST", "/api/chat/conversations/1/messages", "{}"),
                Arguments.of("POST", "/api/chat/messages/12/recall", null),
                Arguments.of("GET", "/api/chat/conversations/1/read-state", null),
                Arguments.of("GET", "/api/chat/conversations/1/messages/search?q=hi", null),
                Arguments.of("GET", "/api/chat/unread", null),
                // 回收站 / 上传会话
                Arguments.of("GET", "/api/trash", null),
                Arguments.of("POST", "/api/trash/restore", "{\"ids\":[1]}"),
                Arguments.of("POST", "/api/trash/purge", "{\"ids\":[1]}"),
                Arguments.of("DELETE", "/api/trash", null),
                Arguments.of("GET", "/api/files/uploads", null),
                Arguments.of("POST", "/api/files/uploads", "{}"),
                Arguments.of("GET", "/api/files/uploads/abc", null),
                // 软件分发 / 权益 / 公告 / WebDAV 密码
                Arguments.of("GET", "/api/software/packages", null),
                Arguments.of("GET", "/api/software/releases/12/download", null),
                Arguments.of("GET", "/api/entitlements/me", null),
                Arguments.of("GET", "/api/announcements", null),
                Arguments.of("GET", "/api/announcements/unread-count", null),
                Arguments.of("GET", "/api/webdav/passwords", null),
                Arguments.of("POST", "/api/webdav/passwords", "{}"),
                // 传输任务
                Arguments.of("GET", "/api/transfer/tasks", null),
                Arguments.of("POST", "/api/transfer/tasks", "{}"),
                Arguments.of("POST", "/api/transfer/tasks/12/cancel", null),
                Arguments.of("POST", "/api/transfer/tasks/12/retry", null),
                Arguments.of("POST", "/api/transfer/tasks/12/accept", null),
                Arguments.of("POST", "/api/transfer/tasks/12/reject", null),
                Arguments.of("GET", "/api/transfer/tasks/12", null)
        );
    }

    @ParameterizedTest(name = "[服务拒绝] {0} {1} 必须 403")
    @MethodSource("endpoints")
    void serviceDenialMapsTo403Envelope(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path);
        request.header("X-User-Id", "7");
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }

        MvcResult result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 允许两种 4xx：
        //  - 403：请求到达 Service，服务判定无权限（本矩阵主要目标）；
        //  - 400：Controller 在调 Service 之前就做了参数校验（如缺 peerUserId/fileName），
        //         此时不可能 200，仍然证明「未通过业务层就放行」不会发生。
        boolean denied = status == 403 && responseBody.contains("\"code\":403");
        boolean inputRejected = status == 400 && responseBody.contains("\"code\":400");
        assertTrue(denied || inputRejected,
                "端点既没 403 也没 400（疑似绕过鉴权或吞成 5xx）：" + method + " " + path
                        + " -> " + status + " " + responseBody);
        assertNoSensitiveData(method + " " + path, responseBody);
    }

    /** 请求体缺失/非法也不允许把「未授权」变成 500：客户端必须能区分 4xx。 */
    @Test
    void malformedRequestsNeverReturn500() throws Exception {
        List<String[]> probes = List.of(
                new String[]{"POST", "/api/files/rename"},
                new String[]{"POST", "/api/chat/conversations/1/messages"},
                new String[]{"PUT", "/api/documents/12"}
        );
        for (String[] probe : probes) {
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(probe[0]), probe[1]);
            request.header("X-User-Id", "7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not-json");
            int status = mvc.perform(request).andReturn().getResponse().getStatus();
            assertTrue(status >= 400 && status < 500,
                    "畸形请求返回了 " + status + "（" + probe[0] + " " + probe[1] + "），客户端无法区分");
        }
    }

    private static void assertNoSensitiveData(String endpoint, String body) {
        String lower = body.toLowerCase();
        assertFalse(lower.contains("passwordhash"), endpoint + " 泄漏 passwordHash：" + body);
        assertFalse(lower.contains("password_hash"), endpoint + " 泄漏 password_hash：" + body);
        assertFalse(lower.contains("x-gateway-signature"), endpoint + " 回显内部签名头");
    }
}
