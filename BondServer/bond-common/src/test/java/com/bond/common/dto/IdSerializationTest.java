package com.bond.common.dto;

import com.bond.common.entity.SystemConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ID 字符串化契约测试：
 * <ul>
 *   <li>ID 类字段序列化为 JSON 字符串（避免 JS 2^53 精度丢失）；</li>
 *   <li>fileSize / maxSize / usedSize / createdAt 等数值字段保持数字；</li>
 *   <li>请求体反序列化同时接受字符串与数字（客户端兼容）。</li>
 * </ul>
 */
class IdSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final long BIG_ID = 1_072_899_559_302_823_936L; // > 2^53，模拟雪花 ID

    @Test
    @DisplayName("UserDTO / Result<UserDTO>：id 输出字符串")
    void userDtoIdIsString() throws Exception {
        UserDTO dto = new UserDTO();
        dto.setId(BIG_ID);
        dto.setUsername("alice");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));
        assertTrue(node.get("id").isTextual(), "id should be textual");
        assertEquals(String.valueOf(BIG_ID), node.get("id").asText());

        Result<UserDTO> result = Result.ok(dto);
        JsonNode root = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(root.get("data").get("id").isTextual(), "nested Result.data.id should be textual");
        assertTrue(root.get("code").isNumber(), "Result.code stays numeric");
    }

    @Test
    @DisplayName("WorkspaceDTO：id/ownerId 字符串，maxSize/usedSize/createdAt 保持数字")
    void workspaceDtoMixedFields() throws Exception {
        WorkspaceDTO dto = new WorkspaceDTO();
        dto.setId(BIG_ID);
        dto.setOwnerId(BIG_ID + 1);
        dto.setMaxSize(5_368_709_120L);
        dto.setUsedSize(1024L);
        dto.setCreatedAt(1_760_000_000_000L);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));
        assertTrue(node.get("id").isTextual());
        assertTrue(node.get("ownerId").isTextual());
        assertTrue(node.get("maxSize").isNumber());
        assertTrue(node.get("usedSize").isNumber());
        assertTrue(node.get("createdAt").isNumber());
    }

    @Test
    @DisplayName("TransferTaskDTO：全部 ID 字段字符串，fileSize/expiresAt 数字，chunks 展开")
    void transferTaskDtoIdsAreStrings() throws Exception {
        TransferTaskDTO dto = new TransferTaskDTO();
        dto.setId(BIG_ID);
        dto.setSenderId(1L);
        dto.setReceiverId(2L);
        dto.setSenderDeviceId(3L);
        dto.setReceiverDeviceId(4L);
        dto.setWorkspaceId(5L);
        dto.setFileSize(1_099_511_627_776L);
        dto.setExpiresAt(1_760_000_000_000L);
        ChunkDTO chunk = new ChunkDTO();
        chunk.setId(BIG_ID + 9);
        chunk.setTaskId(BIG_ID);
        chunk.setChunkSize(5_242_880L);
        dto.setChunks(List.of(chunk));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));
        for (String idField : List.of("id", "senderId", "receiverId", "senderDeviceId", "receiverDeviceId", "workspaceId")) {
            assertTrue(node.get(idField).isTextual(), idField + " should be textual");
        }
        assertTrue(node.get("fileSize").isNumber(), "fileSize stays numeric");
        assertTrue(node.get("expiresAt").isNumber(), "expiresAt stays numeric");
        JsonNode chunkNode = node.get("chunks").get(0);
        assertTrue(chunkNode.get("id").isTextual());
        assertTrue(chunkNode.get("taskId").isTextual());
        assertTrue(chunkNode.get("chunkSize").isNumber());
    }

    @Test
    @DisplayName("InvitationDTO / WorkspaceFileDTO / WorkspaceMemberDTO / DeviceDTO / FriendDTO 的 ID 字段字符串化")
    void otherDtoIdsAreStrings() throws Exception {
        InvitationDTO invitation = new InvitationDTO();
        invitation.setId(BIG_ID);
        invitation.setWorkspaceId(1L);
        invitation.setInviterId(2L);
        invitation.setInviteeId(3L);
        invitation.setExpiresAt(1_760_000_000_000L);
        JsonNode inv = mapper.readTree(mapper.writeValueAsString(invitation));
        assertTrue(inv.get("id").isTextual());
        assertTrue(inv.get("workspaceId").isTextual());
        assertTrue(inv.get("inviterId").isTextual());
        assertTrue(inv.get("inviteeId").isTextual());
        assertTrue(inv.get("expiresAt").isNumber());

        WorkspaceFileDTO file = new WorkspaceFileDTO();
        file.setId(BIG_ID);
        file.setWorkspaceId(1L);
        file.setUploadedBy(2L);
        file.setFileSize(3L);
        JsonNode f = mapper.readTree(mapper.writeValueAsString(file));
        assertTrue(f.get("id").isTextual());
        assertTrue(f.get("workspaceId").isTextual());
        assertTrue(f.get("uploadedBy").isTextual());
        assertTrue(f.get("fileSize").isNumber());

        WorkspaceMemberDTO member = new WorkspaceMemberDTO();
        member.setId(BIG_ID);
        member.setWorkspaceId(1L);
        member.setUserId(2L);
        member.setJoinedAt(1_760_000_000_000L);
        JsonNode m = mapper.readTree(mapper.writeValueAsString(member));
        assertTrue(m.get("id").isTextual());
        assertTrue(m.get("workspaceId").isTextual());
        assertTrue(m.get("userId").isTextual());
        assertTrue(m.get("joinedAt").isNumber());

        DeviceDTO device = new DeviceDTO();
        device.setId(BIG_ID);
        device.setUserId(1L);
        device.setLastSeenAt(1_760_000_000_000L);
        JsonNode d = mapper.readTree(mapper.writeValueAsString(device));
        assertTrue(d.get("id").isTextual());
        assertTrue(d.get("userId").isTextual());
        assertTrue(d.get("lastSeenAt").isNumber());

        FriendDTO friend = new FriendDTO();
        friend.setId(BIG_ID);
        friend.setUserId(1L);
        friend.setCreatedAt(1_760_000_000_000L);
        JsonNode fr = mapper.readTree(mapper.writeValueAsString(friend));
        assertTrue(fr.get("id").isTextual());
        assertTrue(fr.get("userId").isTextual());
        assertTrue(fr.get("createdAt").isNumber());

        FriendRequestDTO request = new FriendRequestDTO();
        request.setId(BIG_ID);
        request.setFromUserId(1L);
        request.setCreatedAt(1_760_000_000_000L);
        JsonNode req = mapper.readTree(mapper.writeValueAsString(request));
        assertTrue(req.get("id").isTextual());
        assertTrue(req.get("fromUserId").isTextual());
        assertTrue(req.get("createdAt").isNumber());

        AdminUserDTO adminUser = new AdminUserDTO();
        adminUser.setId(BIG_ID);
        adminUser.setCreatedAt(1_760_000_000_000L);
        adminUser.setDeviceCount(2);
        JsonNode au = mapper.readTree(mapper.writeValueAsString(adminUser));
        assertTrue(au.get("id").isTextual());
        assertTrue(au.get("createdAt").isNumber());
        assertTrue(au.get("deviceCount").isNumber());
    }

    @Test
    @DisplayName("SystemConfig 被 AdminController 直接序列化：updatedBy 字符串化")
    void systemConfigUpdatedByIsString() throws Exception {
        SystemConfig config = new SystemConfig();
        config.setConfigKey("transfer.chunk_size");
        config.setConfigValue("5242880");
        config.setConfigType("int");
        config.setUpdatedBy(BIG_ID);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(config));
        assertTrue(node.get("updatedBy").isTextual());
    }

    @Test
    @DisplayName("请求体反序列化：字符串与数字 ID 都能解析为 Long")
    void requestDeserializationAcceptsStringAndNumber() throws Exception {
        WorkspaceDTO fromString = mapper.readValue(
                "{\"id\":\"1072899559302823936\",\"ownerId\":\"42\"}", WorkspaceDTO.class);
        assertEquals(1_072_899_559_302_823_936L, fromString.getId());
        assertEquals(42L, fromString.getOwnerId());

        WorkspaceDTO fromNumber = mapper.readValue("{\"id\":123,\"ownerId\":456}", WorkspaceDTO.class);
        assertEquals(123L, fromNumber.getId());
        assertEquals(456L, fromNumber.getOwnerId());

        String json = mapper.writeValueAsString(Result.ok(fromString));
        assertTrue(json.contains("\"1072899559302823936\""), "id must be serialized as a quoted string");
        assertFalse(json.contains(":1072899559302823936"), "id must not appear as a raw JSON number");
    }
}
