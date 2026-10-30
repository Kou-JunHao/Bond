package com.bond.transfer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-121：构建期生成 docs/openapi.json。
 *
 * <p>本测试不启动 Spring 容器、不连数据库；它扫描 Controller 与 DTO 生成静态规格。
 * 运行 `mvn test` 时会把产物写到 `BondServer/docs/openapi.json`（相对 bond-transfer 模块）。</p>
 */
class OpenApiGeneratorTest {

    @Test
    @DisplayName("生成 docs/openapi.json：路径/操作数足够，关键分组存在，且不含运行时端点")
    void generatesStaticOpenApiSpec() throws Exception {
        Path target = OpenApiGenerator.writeToDocs();

        assertTrue(Files.exists(target), "openapi.json 应被生成：" + target);
        String json = Files.readString(target);
        assertTrue(json.length() > 20_000, "规格内容过少，可能扫描失败：" + json.length());

        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals("3.0.3", root.path("openapi").asText());
        JsonNode paths = root.path("paths");
        assertTrue(paths.size() > 80, "路径数异常：" + paths.size());

        // 关键端点必须出现（客户端契约源头）
        assertTrue(paths.has("/api/files"), "缺少 GET /api/files");
        assertTrue(paths.has("/api/files/upload"), "缺少 POST /api/files/upload");
        assertTrue(paths.has("/api/files/folders"), "缺少 POST /api/files/folders");
        assertTrue(paths.has("/api/workspaces"), "缺少工作区列表");
        assertTrue(paths.has("/api/shares"), "缺少分享列表");
        assertTrue(paths.has("/api/software/packages"), "缺少软件分发列表");
        assertTrue(paths.has("/api/chat/conversations"), "缺少聊天会话列表");
        assertTrue(paths.has("/api/admin/users"), "缺少管理端用户列表");
        assertTrue(paths.has("/api/entitlements/me"), "缺少权益接口");

        // 运行时不应暴露 springdoc 端点；静态规格里也不应包含
        assertTrue(!paths.has("/v3/api-docs"), "静态规格不应包含 /v3/api-docs");
        assertTrue(!paths.has("/swagger-ui/index.html"), "静态规格不应包含 swagger-ui");

        // 安全方案：用户端 JWT + WebDAV Basic
        JsonNode schemes = root.path("components").path("securitySchemes");
        assertTrue(schemes.has("bearerAuth"));
        assertTrue(schemes.has("basicAuth"));

        // 关键 DTO schema 必须被登记（客户端按这些字段写）
        JsonNode schemas = root.path("components").path("schemas");
        assertTrue(schemas.has("WorkspaceFileDTO"));
        assertTrue(schemas.has("PageResult") || schemas.has("Result"));
        assertTrue(schemas.path("WorkspaceFileDTO").path("properties").has("fileName"));
        assertTrue(schemas.path("WorkspaceFileDTO").path("properties").has("fileSize"));
        assertTrue(schemas.path("WorkspaceFileDTO").path("properties").has("isDirectory"));
    }
}
