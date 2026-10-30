package com.bond.transfer.share;

import com.bond.common.dto.AdminShareDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.ShareLink;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.AdminShareRow;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.AuditService;
import com.bond.transfer.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** task-89：管理端分享治理服务单测。 */
@ExtendWith(MockitoExtension.class)
class AdminShareServiceTest {

    private static final Long ACTOR_ID = 7L;
    private static final Long OWNER_ID = 11L;

    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private AdminShareService service;

    @Test
    @DisplayName("列表：total 独立 count + join 行映射为 DTO（含公网 URL/毫秒时间）")
    void listMapsRowsAndTotal() {
        OffsetDateTime created = OffsetDateTime.parse("2026-10-20T10:00:00+08:00");
        OffsetDateTime updated = OffsetDateTime.parse("2026-10-21T11:30:00+08:00");
        AdminShareRow row = new AdminShareRow();
        row.setId(42L);
        row.setToken("abcdefghijklmnop");
        row.setFileId(100L);
        row.setFileName("季度报告.pdf");
        row.setOwnerId(OWNER_ID);
        row.setOwnerUsername("alice");
        row.setStatus(0);
        row.setExpiresAt(null);
        row.setDownloadCount(3);
        row.setCreatedAt(created);
        row.setUpdatedAt(updated);

        when(shareLinkMapper.countAdmin(isNull(), eq("alice"), isNull())).thenReturn(1L);
        when(shareLinkMapper.selectAdminPage(isNull(), eq("alice"), isNull(), eq(20), eq(0L)))
                .thenReturn(List.of(row));
        when(configService.get(eq("share.public_base_url"), any())).thenReturn("https://bond.example.com/");

        PageResult<AdminShareDTO> result = service.list(1, 20, null, "  alice  ", null);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(20, result.getSize());
        AdminShareDTO dto = result.getItems().get(0);
        assertEquals(42L, dto.getId());
        assertEquals("abcdefghijklmnop", dto.getToken());
        assertEquals("https://bond.example.com/s/abcdefghijklmnop", dto.getUrl());
        assertEquals("季度报告.pdf", dto.getFileName());
        assertEquals("alice", dto.getOwnerUsername());
        assertEquals(0, dto.getStatus());
        assertEquals(3, dto.getDownloadCount());
        assertNull(dto.getExpiresAt());
        assertEquals(created.toInstant().toEpochMilli(), dto.getCreatedAt());
        assertEquals(updated.toInstant().toEpochMilli(), dto.getLastAccessAt());
    }

    @Test
    @DisplayName("列表：page/size 边界收敛（page<1→1，size>100→100）")
    void listClampsPaging() {
        when(shareLinkMapper.countAdmin(any(), any(), any())).thenReturn(0L);

        PageResult<AdminShareDTO> result = service.list(0, 500, 1, null, null);

        assertEquals(1, result.getPage());
        assertEquals(100, result.getSize());
        assertEquals(0L, result.getTotal());
        assertEquals(List.of(), result.getItems());
    }

    @Test
    @DisplayName("列表：total=0 时不查明细（避免无意义查询）")
    void listSkipsDetailWhenEmpty() {
        when(shareLinkMapper.countAdmin(eq(1), isNull(), isNull())).thenReturn(0L);

        service.list(1, 20, 1, null, null);

        verify(shareLinkMapper, never()).selectAdminPage(any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("强制撤销：写审计日志（action=share.revoke，detail 含 token 前缀与 owner）")
    void revokeWritesAuditLog() {
        ShareLink link = shareLink(0);
        when(shareLinkMapper.selectById(42L)).thenReturn(link);
        when(shareLinkMapper.revokeById(42L)).thenReturn(1);
        when(userMapper.selectById(ACTOR_ID)).thenReturn(user("admin", "管理员"));
        when(userMapper.selectById(OWNER_ID)).thenReturn(user("alice", null));

        service.revoke(42L, ACTOR_ID, "203.0.113.9");

        verify(shareLinkMapper).revokeById(42L);
        verify(auditService).record(eq(ACTOR_ID), eq("管理员"), eq("share.revoke"), eq("share_link"),
                eq(42L), argThatDetail("abcdefgh", OWNER_ID, "alice"), eq("203.0.113.9"));
    }

    @Test
    @DisplayName("强制撤销：已撤销再删 → 404，且不再写库")
    void revokeAlreadyRevoked404() {
        when(shareLinkMapper.selectById(42L)).thenReturn(shareLink(1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.revoke(42L, ACTOR_ID, "127.0.0.1"));
        assertEquals(404, ex.getCode());
        verify(shareLinkMapper, never()).revokeById(anyLong());
        verify(auditService, never()).record(anyLong(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("强制撤销：不存在 → 404")
    void revokeMissing404() {
        when(shareLinkMapper.selectById(42L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.revoke(42L, ACTOR_ID, "127.0.0.1"));
        assertEquals(404, ex.getCode());
        verify(shareLinkMapper, never()).revokeById(anyLong());
    }

    private static ShareLink shareLink(int status) {
        ShareLink link = new ShareLink();
        link.setId(42L);
        link.setToken("abcdefghijklmnop");
        link.setCreatorId(OWNER_ID);
        link.setStatus(status);
        link.setDownloadCount(0);
        return link;
    }

    private static User user(String username, String nickname) {
        User user = new User();
        user.setUsername(username);
        user.setNickname(nickname);
        return user;
    }

    private static String argThatDetail(String tokenPrefix, Long ownerId, String ownerUsername) {
        return org.mockito.ArgumentMatchers.argThat(detail -> detail != null
                && detail.contains("\"tokenPrefix\":\"" + tokenPrefix + "\"")
                && detail.contains("\"ownerId\":" + ownerId)
                && detail.contains("\"ownerUsername\":\"" + ownerUsername + "\""));
    }
}
