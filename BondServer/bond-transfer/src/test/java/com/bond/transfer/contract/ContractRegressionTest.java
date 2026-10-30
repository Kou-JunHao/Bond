package com.bond.transfer.contract;

import com.bond.common.dto.ChatMessageDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.dto.Result;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.transfer.controller.AdminShareController;
import com.bond.transfer.controller.EmailAdminController;
import com.bond.transfer.controller.PersonalFileController;
import com.bond.transfer.controller.UploadSessionController;
import com.bond.transfer.dto.EmailDeliveryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-121：API 契约回归测试。
 *
 * <p>把三条“客户端按猜测写”的真实事故变成可执行契约：</p>
 * <ol>
 *   <li>{@link WorkspaceFileDTO} 字段名是 fileName/fileSize（不是 name/size）；</li>
 *   <li>{@link ChatMessageDTO} 字段集合固定（HTTP 与 WS 共用）；</li>
 *   <li>列表端点返回“裸数组”还是 {@link PageResult} 信封。</li>
 * </ol>
 */
class ContractRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("WorkspaceFileDTO 字段集合固定：fileName/fileSize/isDirectory，Long 主键序列化为字符串")
    void workspaceFileDtoContract() throws Exception {
        assertEquals(Set.of("id", "workspaceId", "fileName", "fileSize", "minioPath", "contentType",
                        "uploadedBy", "parentPath", "isDirectory", "fileHash", "createdAt"),
                propertyNames(WorkspaceFileDTO.class));

        WorkspaceFileDTO dto = new WorkspaceFileDTO();
        dto.setId(123L);
        dto.setFileSize(4_194_304L);
        dto.setFileName("a.bin");
        dto.setIsDirectory(true);
        String json = objectMapper.writeValueAsString(dto);
        assertTrue(json.contains("\"fileName\""), json);
        assertTrue(json.contains("\"fileSize\":4194304"), json);
        assertTrue(json.contains("\"isDirectory\":true"), json);
        assertTrue(json.contains("\"id\":\"123\""), json);
        assertFalse(json.contains("\"name\""), json);
        assertFalse(json.contains("\"size\""), json);
    }

    @Test
    @DisplayName("ChatMessageDTO 字段集合固定（HTTP 列表/发送响应与 WS 帧共用同一 DTO）")
    void chatMessageDtoContract() throws Exception {
        assertEquals(Set.of("id", "conversationId", "senderId", "senderNickname", "kind", "body",
                        "fileId", "fileName", "fileSize", "clientMsgId", "createdAtMillis", "deleted",
                        "recalled", "recalledAtMillis", "fileContentType"),
                propertyNames(ChatMessageDTO.class));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setId(987654321L);
        dto.setFileSize(1024L);
        dto.setKind("file");
        String json = objectMapper.writeValueAsString(dto);
        assertTrue(json.contains("\"id\":\"987654321\""), json);
        assertTrue(json.contains("\"fileSize\":1024"), json);
        assertTrue(json.contains("\"createdAtMillis\""), json);
    }

    @Test
    @DisplayName("EmailDeliveryDTO 是 record，字段集合固定（管理员投递日志）")
    void emailDeliveryDtoContract() {
        assertEquals(Set.of("id", "userId", "toMasked", "purpose", "templateCode", "subject", "status",
                        "failureReason", "variableKeys", "placeholdersLeft", "provider", "durationMs",
                        "attempts", "lastError", "createdAt"),
                Arrays.stream(EmailDeliveryDTO.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Test
    @DisplayName("列表端点形态：个人文件是裸数组，管理端分页是 PageResult 信封")
    void listEndpointShapes() {
        assertWrappedList(PersonalFileController.class, "list", List.class);
        assertWrappedList(UploadSessionController.class, "list", PageResult.class);
        assertWrappedList(AdminShareController.class, "list", PageResult.class);
        assertWrappedList(EmailAdminController.class, "delivery", PageResult.class);
    }

    @Test
    @DisplayName("入库的 openapi.json 存在且包含关键路径（构建期生成，运行时禁用 /v3/api-docs）")
    void committedOpenApiSpecContainsCorePaths() throws Exception {
        Path spec = Path.of("..", "docs", "openapi.json");
        if (!Files.isRegularFile(spec)) {
            // 干净检出的首次构建：先生成再断言（生成器与断言同源，见 OpenApiGenerator）
            OpenApiGenerator.writeToDocs();
        }
        assertTrue(Files.isRegularFile(spec),
                "docs/openapi.json 未生成：请跑 mvn -pl bond-transfer -am test -Dtest=OpenApiGeneratorTest");
        String raw = Files.readString(spec, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = objectMapper.readValue(raw, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        for (String expected : List.of("/api/files", "/api/files/upload", "/api/files/folders",
                "/api/transfer/tasks", "/api/chat/conversations", "/api/shares",
                "/api/documents", "/api/trash", "/api/software/packages")) {
            assertTrue(paths.containsKey(expected), "openapi.json 缺少路径 " + expected);
        }
        assertFalse(paths.containsKey("/v3/api-docs"));
    }

    /** 断言控制器方法返回 {@code Result<inner<...>>}，且 inner 与期望一致。 */
    private static void assertWrappedList(Class<?> controller, String methodName, Class<?> inner) {
        Method method = Arrays.stream(controller.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(controller.getSimpleName() + " 缺少方法 " + methodName));
        assertEquals(Result.class, method.getReturnType(),
                controller.getSimpleName() + "#" + methodName + " 必须返回 Result 信封");
        ParameterizedType outer = (ParameterizedType) method.getGenericReturnType();
        ParameterizedType innerType = (ParameterizedType) outer.getActualTypeArguments()[0];
        assertEquals(inner, innerType.getRawType(),
                controller.getSimpleName() + "#" + methodName + " 的负载类型应为 " + inner.getSimpleName());
    }

    private static Set<String> propertyNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            com.fasterxml.jackson.annotation.JsonProperty property =
                    field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            names.add(property != null && !property.value().isBlank() ? property.value() : field.getName());
        }
        return names;
    }
}
