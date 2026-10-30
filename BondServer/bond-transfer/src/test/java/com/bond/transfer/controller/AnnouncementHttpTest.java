package com.bond.transfer.controller;

import com.bond.common.dto.AnnouncementDTO;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.service.AnnouncementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户侧公告 HTTP 契约测试（任务单 #19）。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementHttpTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AnnouncementService announcementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AnnouncementController(announcementService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static AnnouncementDTO dto(long id, String title, boolean read) {
        AnnouncementDTO dto = new AnnouncementDTO();
        dto.setId(id);
        dto.setTitle(title);
        dto.setContent("正文");
        dto.setLevel("info");
        dto.setPublishedAtMillis(1_700_000_000_000L);
        dto.setRead(read);
        return dto;
    }

    @Test
    void listReturnsEnvelopeWithReadFlag() throws Exception {
        when(announcementService.listForUser(USER_ID))
                .thenReturn(List.of(dto(11L, "系统维护", false), dto(12L, "已读公告", true)));

        mockMvc.perform(get("/api/announcements").header("X-User-Id", String.valueOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("系统维护"))
                .andExpect(jsonPath("$.data[0].read").value(false))
                .andExpect(jsonPath("$.data[1].read").value(true));
    }

    @Test
    void listReturnsEmptyArrayWhenNothingVisible() throws Exception {
        when(announcementService.listForUser(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/announcements").header("X-User-Id", String.valueOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void unreadCountReturnsCountObject() throws Exception {
        when(announcementService.unreadCount(USER_ID)).thenReturn(7L);

        mockMvc.perform(get("/api/announcements/unread-count").header("X-User-Id", String.valueOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.count").value(7));
    }

    @Test
    void markReadCallsService() throws Exception {
        mockMvc.perform(post("/api/announcements/77/read").header("X-User-Id", String.valueOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(announcementService).markRead(USER_ID, 77L);
    }

    @Test
    void markReadMapsBusiness404ToHttp404() throws Exception {
        doThrow(new BusinessException(404, "公告不存在"))
                .when(announcementService).markRead(USER_ID, 99L);

        mockMvc.perform(post("/api/announcements/99/read").header("X-User-Id", String.valueOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("公告不存在"));
    }

    @Test
    void markReadWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(post("/api/announcements/abc/read").header("X-User-Id", String.valueOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
