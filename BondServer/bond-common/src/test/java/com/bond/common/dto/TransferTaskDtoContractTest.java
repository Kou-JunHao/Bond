package com.bond.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-107：传输任务 JSON 契约回归测试。
 *
 * <p>背景（生产事故）：「同一链路混用两种字段命名」——当运行环境的 Jackson 全局命名策略
 * 被配成 {@code SNAKE_CASE}（例如某些外部 application-*.yml / 环境变量），
 * 客户端按 {@code uploadedChunks/chunkCount} 解析就会拿不到值（进度恒为 0）。
 * DTO 上的类级 {@code @JsonNaming(LowerCamelCase)} 必须能覆盖全局策略。</p>
 */
class TransferTaskDtoContractTest {

    private static TransferTaskDTO sample() {
        TransferTaskDTO dto = new TransferTaskDTO();
        dto.setId(5L);
        dto.setSenderId(7L);
        dto.setReceiverId(9L);
        dto.setFileName("report.bin");
        dto.setFileSize(42L);
        dto.setChunkSize(4);
        dto.setChunkCount(3);
        dto.setUploadedChunks(2);
        dto.setDownloadedChunks(1);
        dto.setStatus(1);
        dto.setProgressBytes(8L);
        dto.setTotalBytes(12L);
        dto.setPeerName("peer");
        dto.setCreatedAt(1700000000000L);
        return dto;
    }

    @Test
    @DisplayName("默认 ObjectMapper：字段为 lowerCamelCase")
    void serializesCamelCaseByDefault() throws Exception {
        String json = new ObjectMapper().writeValueAsString(sample());
        assertTrue(json.contains("\"uploadedChunks\":2"), json);
        assertTrue(json.contains("\"chunkCount\":3"), json);
        assertTrue(json.contains("\"fileName\":\"report.bin\""), json);
        assertFalse(json.contains("uploaded_chunks"), json);
        assertFalse(json.contains("chunk_count"), json);
    }

    @Test
    @DisplayName("全局 SNAKE_CASE 策略下仍输出 lowerCamelCase（@JsonNaming 覆盖全局）")
    void camelCaseSurvivesGlobalSnakeCaseStrategy() throws Exception {
        ObjectMapper snake = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        String json = snake.writeValueAsString(sample());
        assertTrue(json.contains("\"uploadedChunks\":2"), json);
        assertTrue(json.contains("\"chunkCount\":3"), json);
        assertTrue(json.contains("\"progressBytes\":8"), json);
        assertTrue(json.contains("\"createdAt\":1700000000000"), json);
        assertFalse(json.contains("uploaded_chunks"), json);
        assertFalse(json.contains("chunk_count"), json);
        assertFalse(json.contains("created_at"), json);
    }

    @Test
    @DisplayName("ID 字段按字符串序列化（超出 JS 安全整数位）")
    void idsSerializedAsStrings() throws Exception {
        String json = new ObjectMapper().writeValueAsString(sample());
        assertTrue(json.contains("\"id\":\"5\""), json);
        assertTrue(json.contains("\"senderId\":\"7\""), json);
    }
}
