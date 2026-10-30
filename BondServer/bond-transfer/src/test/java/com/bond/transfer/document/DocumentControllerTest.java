package com.bond.transfer.document;

import com.bond.common.dto.DocumentDTO;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.controller.DocumentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 文档接口：路由/状态码/导出头（standalone MockMvc，不测网关签名；不用 json-path）。 */
class DocumentControllerTest {

    private static final Long USER = 1L;
    private final DocumentService service = mock(DocumentService.class);

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new DocumentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static DocumentDTO dto() {
        DocumentDTO dto = new DocumentDTO();
        dto.setId(9L);
        dto.setWorkspaceId(5L);
        dto.setTitle("a.md");
        dto.setVersion(1L);
        dto.setContentMd("body");
        dto.setSizeBytes(4L);
        return dto;
    }

    @Test
    @DisplayName("POST /api/documents → 200（id 以字符串序列化）")
    void createReturnsOk() throws Exception {
        when(service.create(eq(USER), eq(5L), any(), any(), any(), any(), any())).thenReturn(dto());
        mockMvc().perform(post("/api/documents")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":5,\"title\":\"a.md\",\"contentMd\":\"body\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":200")))
                .andExpect(content().string(containsString("\"id\":\"9\"")));
    }

    @Test
    @DisplayName("PUT 缺 version 且非 force → 400")
    void saveWithoutVersionReturns400() throws Exception {
        when(service.save(eq(USER), eq(9L), any(), any(), any(), any(), eq(null), anyBoolean()))
                .thenThrow(new BusinessException(400, "version 不能为空"));
        mockMvc().perform(put("/api/documents/9")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("\"code\":400")));
    }

    @Test
    @DisplayName("PUT 冲突 → 409 + data.serverVersion")
    void saveConflictReturnsPayload() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverVersion", 3L);
        payload.put("lastEditorName", "小明");
        payload.put("updatedAt", 123456789L);
        when(service.save(eq(USER), eq(9L), any(), any(), any(), any(), eq(1L), anyBoolean()))
                .thenThrow(new ConflictException("文档已被他人修改", payload));
        mockMvc().perform(put("/api/documents/9")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"x\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("\"code\":409")))
                .andExpect(content().string(containsString("\"serverVersion\":3")));
    }

    @Test
    @DisplayName("GET /{id}/export?format=md → attachment + text/markdown")
    void exportMarkdown() throws Exception {
        when(service.export(eq(USER), eq(9L), eq("md"))).thenReturn(
                new DocumentService.ExportFile("hi".getBytes(StandardCharsets.UTF_8),
                        "text/markdown; charset=UTF-8", "a.md"));
        mockMvc().perform(get("/api/documents/9/export").param("format", "md").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Type", containsString("text/markdown")));
    }

    @Test
    @DisplayName("DELETE /api/documents/{id} → 200")
    void deleteReturnsOk() throws Exception {
        mockMvc().perform(delete("/api/documents/9").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":200")));
    }

    @Test
    @DisplayName("GET /{id}/versions → 200")
    void versionsReturnsOk() throws Exception {
        when(service.versions(eq(USER), eq(9L), eq(20), eq(0))).thenReturn(java.util.List.of());
        mockMvc().perform(get("/api/documents/9/versions").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":200")));
    }
}
