package com.bond.transfer.controller;

import com.bond.common.dto.ChatConversationDTO;
import com.bond.common.dto.ChatMessageDTO;
import com.bond.common.dto.ChatReadStateDTO;
import com.bond.common.dto.ChatSearchItemDTO;
import com.bond.common.dto.ChatUnreadDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.chat.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 轻聊天 REST（/api/chat/**，经网关；WS 走 /ws/chat 直连 transfer）。
 *
 * @see com.bond.transfer.chat.ChatService
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/conversations")
    public Result<List<ChatConversationDTO>> listConversations(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(chatService.listConversations(userId));
    }

    /** MVP 只支持 1:1 会话；工作区频道会随会话列表自动出现。 */
    @PostMapping("/conversations")
    public Result<ChatConversationDTO> createConversation(@RequestHeader("X-User-Id") Long userId,
                                                          @RequestBody(required = false) CreateConversationRequest request) {
        if (request == null || request.peerUserId() == null) {
            throw new com.bond.common.exception.BusinessException(400, "缺少 peerUserId");
        }
        if (request.type() != null && !"direct".equals(request.type())) {
            throw new com.bond.common.exception.BusinessException(400, "MVP 只支持 direct 会话");
        }
        return Result.ok(chatService.createOrGetDirect(userId, request.peerUserId()));
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<List<ChatMessageDTO>> listMessages(@RequestHeader("X-User-Id") Long userId,
                                                     @PathVariable Long id,
                                                     @RequestParam(required = false) Long beforeId,
                                                     @RequestParam(required = false) Long afterId,
                                                     @RequestParam(required = false) Integer limit) {
        return Result.ok(chatService.listMessages(id, userId, beforeId, afterId, limit));
    }

    @PostMapping("/conversations/{id}/messages")
    public Result<ChatMessageDTO> sendMessage(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id,
                                              @RequestBody(required = false) SendMessageRequest request) {
        if (request == null) {
            throw new com.bond.common.exception.BusinessException(400, "缺少请求体");
        }
        return Result.ok(chatService.sendMessage(id, userId, request.kind(), request.body(),
                request.fileId(), request.clientMsgId()));
    }

    @PostMapping("/conversations/{id}/read")
    public Result<Void> markRead(@RequestHeader("X-User-Id") Long userId,
                                 @PathVariable Long id,
                                 @RequestBody(required = false) ReadRequest request) {
        if (request == null || request.messageId() == null) {
            throw new com.bond.common.exception.BusinessException(400, "缺少 messageId");
        }
        chatService.markRead(id, userId, request.messageId());
        return Result.ok();
    }

    @DeleteMapping("/messages/{id}")
    public Result<Void> deleteMessage(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        chatService.deleteMessage(id, userId);
        return Result.ok();
    }

    // ---------------------------------------------------------------- task-136 增强

    /** 撤回本人 2 分钟内的消息；已撤回幂等返回占位 DTO。 */
    @PostMapping("/messages/{id}/recall")
    public Result<ChatMessageDTO> recallMessage(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(chatService.recallMessage(id, userId));
    }

    /** 会话已读回执：每个成员最后读到的消息 id 与时间。 */
    @GetMapping("/conversations/{id}/read-state")
    public Result<ChatReadStateDTO> readState(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(chatService.readState(id, userId));
    }

    /** 会话能力：置顶（每人上限 20）/免打扰，均为当前用户维度；未传字段保持不变。 */
    @PatchMapping("/conversations/{id}")
    public Result<ChatConversationDTO> patchConversation(@RequestHeader("X-User-Id") Long userId,
                                                         @PathVariable Long id,
                                                         @RequestBody(required = false) PatchConversationRequest request) {
        if (request == null) {
            throw new com.bond.common.exception.BusinessException(400, "缺少请求体");
        }
        return Result.ok(chatService.patchConversation(id, userId, request.pinned(), request.muted()));
    }

    /** 会话内消息搜索（前缀匹配，仅未删除/未撤回文本消息）。 */
    @GetMapping("/conversations/{id}/messages/search")
    public Result<List<ChatSearchItemDTO>> searchMessages(@RequestHeader("X-User-Id") Long userId,
                                                          @PathVariable Long id,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) Integer limit) {
        return Result.ok(chatService.searchMessages(id, userId, q, limit));
    }

    /** 未读聚合：总数 + 按会话明细，供客户端侧栏徽标一次刷新。 */
    @GetMapping("/unread")
    public Result<ChatUnreadDTO> unread(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(chatService.unreadSummary(userId));
    }

    public record CreateConversationRequest(String type, Long peerUserId) {
    }

    public record SendMessageRequest(String kind, String body, Long fileId, String clientMsgId) {
    }

    public record ReadRequest(Long messageId) {
    }

    public record PatchConversationRequest(Boolean pinned, Boolean muted) {
    }
}
