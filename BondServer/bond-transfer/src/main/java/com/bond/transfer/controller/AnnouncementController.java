package com.bond.transfer.controller;

import com.bond.common.dto.AnnouncementDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户侧公告接口（任务单 #19）。
 *
 * <p>注意：{@code GET /api/announcements} 只读取、不自动标已读；已读由客户端显式调用
 * {@code POST /api/announcements/{id}/read} 上报（幂等）。</p>
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping
    public Result<List<AnnouncementDTO>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(announcementService.listForUser(userId));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(Map.of("count", announcementService.unreadCount(userId)));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        announcementService.markRead(userId, id);
        return Result.ok();
    }
}
