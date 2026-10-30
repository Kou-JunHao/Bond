package com.bond.transfer.service;

import com.bond.common.dto.AnnouncementDTO;
import com.bond.common.dto.AnnouncementUpsertRequest;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.Announcement;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.AnnouncementMapper;
import com.bond.transfer.mapper.AnnouncementReadCountRow;
import com.bond.transfer.mapper.AnnouncementRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公告服务单测（任务单 #19）。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ADMIN_ID = 1L;

    @Mock
    private AnnouncementMapper announcementMapper;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private AnnouncementService announcementService;

    private static AnnouncementRow row(long id, String title, boolean read) {
        AnnouncementRow row = new AnnouncementRow();
        row.setId(id);
        row.setTitle(title);
        row.setContent("正文");
        row.setLevel("info");
        row.setPublishedAt(OffsetDateTime.now().minusHours(1));
        row.setExpiresAt(null);
        row.setRead(read);
        return row;
    }

    private static AnnouncementUpsertRequest request(String title, String content, String level) {
        AnnouncementUpsertRequest req = new AnnouncementUpsertRequest();
        req.setTitle(title);
        req.setContent(content);
        req.setLevel(level);
        return req;
    }

    private static AnnouncementReadCountRow readCount(long announcementId, long count) {
        AnnouncementReadCountRow row = new AnnouncementReadCountRow();
        row.setAnnouncementId(announcementId);
        row.setCnt(count);
        return row;
    }

    @Test
    void listForUserMapsVisibleRowsIncludingReadFlag() {
        when(announcementMapper.selectVisible(eq(USER_ID), anyInt()))
                .thenReturn(List.of(row(11L, "已读公告", true), row(12L, "未读公告", false)));

        List<AnnouncementDTO> items = announcementService.listForUser(USER_ID);

        assertEquals(2, items.size());
        assertEquals(11L, items.get(0).getId());
        assertTrue(items.get(0).isRead());
        assertFalse(items.get(1).isRead());
        assertNotNull(items.get(1).getPublishedAtMillis());
        assertEquals("info", items.get(1).getLevel());
    }

    @Test
    void unreadCountDelegatesToSingleSql() {
        when(announcementMapper.countUnread(USER_ID)).thenReturn(3L);

        assertEquals(3L, announcementService.unreadCount(USER_ID));
    }

    @Test
    void markReadRejectsDraft() {
        Announcement draft = new Announcement();
        draft.setId(99L);
        draft.setPublishedAt(null);
        when(announcementMapper.selectById(99L)).thenReturn(draft);

        BusinessException e = assertThrows(BusinessException.class,
                () -> announcementService.markRead(USER_ID, 99L));

        assertEquals(404, e.getCode());
        verify(announcementMapper, never()).markRead(anyLong(), anyLong(), anyLong());
    }

    @Test
    void markReadRejectsExpired() {
        Announcement expired = new Announcement();
        expired.setId(98L);
        expired.setPublishedAt(OffsetDateTime.now().minusDays(10));
        expired.setExpiresAt(OffsetDateTime.now().minusDays(1));
        when(announcementMapper.selectById(98L)).thenReturn(expired);

        BusinessException e = assertThrows(BusinessException.class,
                () -> announcementService.markRead(USER_ID, 98L));

        assertEquals(404, e.getCode());
    }

    @Test
    void markReadIsIdempotent() {
        Announcement published = new Announcement();
        published.setId(77L);
        published.setPublishedAt(OffsetDateTime.now().minusMinutes(5));
        when(announcementMapper.selectById(77L)).thenReturn(published);

        announcementService.markRead(USER_ID, 77L);
        announcementService.markRead(USER_ID, 77L);

        // 两次都走 ON CONFLICT DO NOTHING 的同一 SQL，不抛异常。
        verify(announcementMapper, org.mockito.Mockito.times(2)).markRead(anyLong(), eq(77L), eq(USER_ID));
    }

    @Test
    void createValidatesTitleAndContentAndLevel() {
        BusinessException blankTitle = assertThrows(BusinessException.class,
                () -> announcementService.create(ADMIN_ID, request("  ", "正文", "info")));
        assertEquals(400, blankTitle.getCode());

        BusinessException blankContent = assertThrows(BusinessException.class,
                () -> announcementService.create(ADMIN_ID, request("标题", " ", "info")));
        assertEquals(400, blankContent.getCode());

        BusinessException badLevel = assertThrows(BusinessException.class,
                () -> announcementService.create(ADMIN_ID, request("标题", "正文", "danger")));
        assertEquals(400, badLevel.getCode());
    }

    @Test
    void createAppliesDefaultsAndNormalizesLevel() {
        AnnouncementUpsertRequest req = request(" 维护通知 ", "今晚 23:00 维护", " WARN ");
        announcementService.create(ADMIN_ID, req);

        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementMapper).insert(captor.capture());
        Announcement saved = captor.getValue();
        assertEquals("维护通知", saved.getTitle());
        assertEquals("warn", saved.getLevel());
        assertEquals(ADMIN_ID, saved.getCreatedBy());
        assertNotNull(saved.getPublishedAt());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void createRejectsContentLongerThanConfiguredLimit() {
        when(configService.getInt("announcement.max_content_chars", 10_000)).thenReturn(5);

        BusinessException e = assertThrows(BusinessException.class,
                () -> announcementService.create(ADMIN_ID, request("标题", "abcdef", "info")));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("5"));
    }

    @Test
    void updateReturns404WhenMissing() {
        when(announcementMapper.selectById(404L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> announcementService.update(ADMIN_ID, 404L, request("标题", "正文", "info")));

        assertEquals(404, e.getCode());
    }

    @Test
    void updateReturnsReadCount() {
        Announcement existing = new Announcement();
        existing.setId(55L);
        existing.setCreatedAt(OffsetDateTime.now().minusHours(2));
        when(announcementMapper.selectById(55L)).thenReturn(existing);
        when(announcementMapper.countReads(List.of(55L))).thenReturn(List.of(readCount(55L, 7L)));

        AnnouncementDTO dto = announcementService.update(ADMIN_ID, 55L, request("新标题", "新正文", "info"));

        assertEquals(7L, dto.getReadCount());
        assertEquals("新标题", dto.getTitle());
    }

    @Test
    void adminListClampsPageSize() {
        Announcement one = new Announcement();
        one.setId(1L);
        one.setTitle("t");
        one.setContent("c");
        one.setLevel("info");
        one.setCreatedAt(OffsetDateTime.now());
        when(announcementMapper.selectList(any())).thenReturn(List.of(one));
        when(announcementMapper.selectCount(any())).thenReturn(1L);
        when(announcementMapper.countReads(List.of(1L))).thenReturn(List.of(readCount(1L, 2L)));

        PageResult<AnnouncementDTO> page = announcementService.listForAdmin(0, 999);

        assertEquals(1, page.getPage());
        assertEquals(100, page.getSize());
        assertEquals(1L, page.getTotal());
        assertEquals(2L, page.getItems().get(0).getReadCount());
    }

    @Test
    void deleteReturns404WhenMissing() {
        when(announcementMapper.selectById(404L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> announcementService.delete(ADMIN_ID, 404L));

        assertEquals(404, e.getCode());
        verify(announcementMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteExisting() {
        Announcement existing = new Announcement();
        existing.setId(66L);
        when(announcementMapper.selectById(66L)).thenReturn(existing);

        announcementService.delete(ADMIN_ID, 66L);

        verify(announcementMapper).deleteById(66L);
    }

    @Test
    @DisplayName("task-109：expiresAt=null 时显式清空过期时间（MP updateById 不写 null）")
    void updateWithNullExpiresAtClearsColumn() {
        Announcement existing = new Announcement();
        existing.setId(77L);
        existing.setTitle("旧标题");
        existing.setContent("旧正文");
        existing.setLevel("info");
        existing.setCreatedAt(OffsetDateTime.now().minusDays(1));
        existing.setExpiresAt(OffsetDateTime.now().plusDays(3));
        when(announcementMapper.selectById(77L)).thenReturn(existing);
        when(announcementMapper.countReads(List.of(77L))).thenReturn(List.of());

        AnnouncementDTO dto = announcementService.update(ADMIN_ID, 77L, request("新标题", "新正文", "info"));

        assertNull(dto.getExpiresAtMillis(), "传 null 表示取消过期时间，返回体不应还带旧时间");
        verify(announcementMapper).updateById(existing);
        verify(announcementMapper).clearExpiresAt(77L);
    }
}
