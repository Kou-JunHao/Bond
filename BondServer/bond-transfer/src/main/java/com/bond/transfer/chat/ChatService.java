package com.bond.transfer.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.ChatConversationDTO;
import com.bond.common.dto.ChatMessageDTO;
import com.bond.common.dto.ChatReadStateDTO;
import com.bond.common.dto.ChatSearchItemDTO;
import com.bond.common.dto.ChatUnreadDTO;
import com.bond.common.entity.ChatConversation;
import com.bond.common.entity.ChatMember;
import com.bond.common.entity.ChatMessage;
import com.bond.common.entity.Friendship;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.RateLimitedException;
import com.bond.transfer.mapper.ChatConversationMapper;
import com.bond.transfer.mapper.ChatConversationRow;
import com.bond.transfer.mapper.ChatMemberMapper;
import com.bond.transfer.mapper.ChatMessageMapper;
import com.bond.transfer.mapper.FriendshipMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 轻聊天核心：会话列表 / 建 1:1 会话 / 消息键集分页 / 发送（含幂等与限流）/ 已读 / 软删 / WS 广播。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    static final String TYPE_DIRECT = "direct";
    static final String TYPE_WORKSPACE = "workspace";
    static final String KIND_TEXT = "text";
    static final String KIND_FILE = "file";
    /** task-136：图片消息复用 workspace_files（contentType 必须 image/*）。 */
    static final String KIND_IMAGE = "image";
    static final String CFG_MESSAGE_MAX_CHARS = "chat.message_max_chars";
    static final int DEFAULT_MESSAGE_MAX_CHARS = 4000;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;
    /** 撤回时限（秒）：本人 2 分钟内。 */
    static final long RECALL_WINDOW_SECONDS = 120L;
    /** 每个用户最多置顶会话数。 */
    static final int PINNED_MAX = 20;
    /** 消息搜索关键词上限（字符）与结果条数边界。 */
    static final int SEARCH_MAX_QUERY_CHARS = 64;
    static final int SEARCH_DEFAULT_LIMIT = 20;
    static final int SEARCH_MAX_LIMIT = 50;
    private static final java.util.Locale ROOT_LOCALE = java.util.Locale.ROOT;

    private final ChatConversationMapper conversationMapper;
    private final ChatMemberMapper memberMapper;
    private final ChatMessageMapper messageMapper;
    private final FriendshipMapper friendshipMapper;
    private final UserMapper userMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final WorkspaceMapper workspaceMapper;
    private final PersonalSpaceService personalSpaceService;
    private final ConfigService configService;
    private final ChatRateLimiter rateLimiter;
    private final ChatWsHub wsHub;

    // ------------------------------------------------------------------ 会话

    public List<ChatConversationDTO> listConversations(Long userId) {
        ensureWorkspaceChannels(userId);
        List<ChatConversationRow> rows = conversationMapper.selectForUser(userId);
        // task-55：SQL 已 JOIN workspaces 过滤 team（防御历史数据），这里再做一次内存兜底：
        // 频道对应的 workspace 已不存在/已非 team 时直接丢弃，避免旧版本遗留的“个人空间频道”重现。
        Set<Long> channelWorkspaceIds = rows.stream()
                .filter(row -> TYPE_WORKSPACE.equals(row.getType()) && row.getWorkspaceId() != null)
                .map(ChatConversationRow::getWorkspaceId)
                .collect(Collectors.toSet());
        Map<Long, Workspace> channelWorkspaces = channelWorkspaceIds.isEmpty()
                ? Map.of()
                : workspaceMapper.selectBatchIds(channelWorkspaceIds).stream()
                        .collect(Collectors.toMap(Workspace::getId, ws -> ws, (a, b) -> a));
        return rows.stream()
                .filter(row -> isVisibleChannel(row, channelWorkspaces))
                .map(this::toConversationDto)
                .collect(Collectors.toList());
    }

    /** 1:1 会话始终可见；workspace 频道必须是现存且 type=team 的工作区。 */
    private static boolean isVisibleChannel(ChatConversationRow row, Map<Long, Workspace> workspaces) {
        if (!TYPE_WORKSPACE.equals(row.getType())) {
            return true;
        }
        Workspace workspace = workspaces.get(row.getWorkspaceId());
        return workspace != null && WorkspaceType.TEAM.getValue().equals(workspace.getType());
    }

    public ChatConversationDTO createOrGetDirect(Long userId, Long peerUserId) {
        if (peerUserId == null || peerUserId.equals(userId)) {
            throw new BusinessException(400, "peerUserId 无效");
        }
        User peer = userMapper.selectById(peerUserId);
        if (peer == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!areFriends(userId, peerUserId)) {
            throw new BusinessException(403, "只能和好友发起聊天");
        }
        String directKey = directKey(userId, peerUserId);
        ChatConversation conversation = conversationMapper.selectDirect(directKey);
        if (conversation == null) {
            conversation = new ChatConversation();
            conversation.setType(TYPE_DIRECT);
            conversation.setDirectKey(directKey);
            conversation.setCreatedBy(userId);
            try {
                conversationMapper.insert(conversation);
                insertMember(conversation.getId(), userId, 0L);
                insertMember(conversation.getId(), peerUserId, 0L);
            } catch (DuplicateKeyException e) {
                // 并发创建：唯一索引兜底，回读已存在的会话
                conversation = conversationMapper.selectDirect(directKey);
                if (conversation == null) {
                    throw e;
                }
            }
        }
        return loadConversation(userId, conversation.getId());
    }

    // ------------------------------------------------------------------ 消息

    public List<ChatMessageDTO> listMessages(Long conversationId, Long userId, Long beforeId, Long afterId, Integer limit) {
        ChatConversation conversation = ensureParticipant(conversationId, userId);
        int size = normalizeLimit(limit);
        List<ChatMessage> messages;
        if (afterId != null) {
            messages = messageMapper.selectAfter(conversationId, afterId, size);
        } else if (beforeId != null) {
            // 复制为可变列表：MyBatis 返回 ArrayList，但单测/未来实现可能返回不可变列表
            messages = new java.util.ArrayList<>(messageMapper.selectBefore(conversationId, beforeId, size));
            java.util.Collections.reverse(messages);
        } else {
            messages = new java.util.ArrayList<>(messageMapper.selectLatest(conversationId, size));
            java.util.Collections.reverse(messages);
        }
        return enrich(messages);
    }

    @Transactional
    public ChatMessageDTO sendMessage(Long conversationId, Long userId, String kind, String body,
                                      Long fileId, String clientMsgId) {
        ChatConversation conversation = ensureParticipant(conversationId, userId);

        long retryAfter = rateLimiter.acquire(userId);
        if (retryAfter > 0L) {
            throw new RateLimitedException("发送过于频繁，请稍后再试", retryAfter);
        }

        if (clientMsgId != null && clientMsgId.length() > 64) {
            throw new BusinessException(400, "clientMsgId 过长");
        }
        if (clientMsgId != null && !clientMsgId.isBlank()) {
            ChatMessage existing = messageMapper.selectByClientMsgId(conversationId, clientMsgId);
            if (existing != null) {
                return toMessageDto(existing);
            }
        }

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderId(userId);
        message.setClientMsgId(clientMsgId);
        message.setCreatedAt(OffsetDateTime.now());
        if (KIND_FILE.equals(kind) || KIND_IMAGE.equals(kind)) {
            WorkspaceFile file = requireChatFile(conversation, userId, fileId);
            if (KIND_IMAGE.equals(kind) && (file.getContentType() == null
                    || !file.getContentType().toLowerCase(ROOT_LOCALE).startsWith("image/"))) {
                throw new BusinessException(400, "图片消息需要图片文件");
            }
            message.setKind(kind);
            message.setFileId(file.getId());
        } else if (KIND_TEXT.equals(kind)) {
            String text = body == null ? "" : body;
            if (text.isBlank()) {
                throw new BusinessException(400, "消息内容不能为空");
            }
            int maxChars = configService.getInt(CFG_MESSAGE_MAX_CHARS, DEFAULT_MESSAGE_MAX_CHARS);
            if (maxChars > 0 && text.length() > maxChars) {
                throw new BusinessException(400, "消息过长（上限 " + maxChars + " 字）");
            }
            message.setKind(KIND_TEXT);
            message.setBody(text);
        } else {
            throw new BusinessException(400, "不支持的 kind: " + kind);
        }

        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            ChatMessage existing = messageMapper.selectByClientMsgId(conversationId, clientMsgId);
            if (existing != null) {
                return toMessageDto(existing);
            }
            throw e;
        }
        conversationMapper.updateLastMessage(conversationId, message.getId());

        ChatMessageDTO dto = toMessageDto(message);
        List<Long> targets = participantIds(conversation).stream()
                .filter(id -> !id.equals(userId))
                .collect(Collectors.toList());
        afterCommit(() -> wsHub.sendToUsers(targets, "msg", dto));
        return dto;
    }

    @Transactional
    public void markRead(Long conversationId, Long userId, Long messageId) {
        ChatConversation conversation = ensureParticipant(conversationId, userId);
        if (messageId == null || messageId <= 0L) {
            throw new BusinessException(400, "messageId 无效");
        }
        long maxId = messageMapper.maxId(conversationId);
        long target = Math.min(messageId, maxId);
        memberMapper.upsertRead(conversationId, userId, target);
        List<Long> targets = participantIds(conversation);
        Map<String, Object> frame = Map.of(
                "conversationId", String.valueOf(conversationId),
                "userId", String.valueOf(userId),
                "messageId", String.valueOf(target));
        afterCommit(() -> wsHub.sendToUsers(targets, "read", frame));
    }

    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException(404, "消息不存在");
        }
        if (!message.getSenderId().equals(userId)) {
            throw new BusinessException(403, "只能删除自己的消息");
        }
        if (message.getDeletedAt() != null) {
            return;
        }
        messageMapper.softDelete(messageId, OffsetDateTime.now());
        ChatConversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            return;
        }
        List<Long> targets = participantIds(conversation);
        Map<String, Object> frame = new HashMap<>();
        frame.put("id", String.valueOf(messageId));
        frame.put("conversationId", String.valueOf(message.getConversationId()));
        frame.put("deleted", true);
        afterCommit(() -> wsHub.sendToUsers(targets, "msg", frame));
    }

    /**
     * task-136 撤回：本人 2 分钟内；他人是否已读不影响；已撤回幂等返回占位 DTO；
     * 已删除的消息返回 404（删除与撤回互斥）。正文/文件引用清空但保留占位行。
     */
    @Transactional
    public ChatMessageDTO recallMessage(Long messageId, Long userId) {
        ChatMessage message = messageMapper.selectOneById(messageId);
        if (message == null || message.getDeletedAt() != null) {
            throw new BusinessException(404, "消息不存在");
        }
        if (!message.getSenderId().equals(userId)) {
            throw new BusinessException(403, "只能撤回自己的消息");
        }
        ChatConversation conversation = ensureParticipant(message.getConversationId(), userId);
        if (Boolean.TRUE.equals(message.getRecalled())) {
            return toMessageDto(message);
        }
        OffsetDateTime created = message.getCreatedAt();
        if (created != null && created.plusSeconds(RECALL_WINDOW_SECONDS).isBefore(OffsetDateTime.now())) {
            throw new BusinessException(400, "超过撤回时限（2 分钟内可撤回）");
        }
        messageMapper.recall(messageId, OffsetDateTime.now());
        ChatMessage refreshed = messageMapper.selectOneById(messageId);
        ChatMessageDTO dto = toMessageDto(refreshed == null ? message : refreshed);
        List<Long> targets = participantIds(conversation);
        Map<String, Object> frame = new HashMap<>();
        frame.put("id", String.valueOf(messageId));
        frame.put("conversationId", String.valueOf(message.getConversationId()));
        frame.put("kind", dto.getKind());
        frame.put("recalled", true);
        frame.put("recalledAtMillis", dto.getRecalledAtMillis());
        afterCommit(() -> wsHub.sendToUsers(targets, "msg", frame));
        return dto;
    }

    /** task-136 已读回执：每个成员的 lastReadMessageId/lastReadAt（仅会话成员可读）。 */
    public ChatReadStateDTO readState(Long conversationId, Long userId) {
        ChatConversation conversation = ensureParticipant(conversationId, userId);
        List<Long> memberIds = participantIds(conversation);
        Map<Long, User> users = memberIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(memberIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        List<ChatReadStateDTO.MemberState> states = new ArrayList<>();
        for (Long memberId : memberIds) {
            ChatMember member = memberMapper.select(conversationId, memberId);
            User user = users.get(memberId);
            ChatReadStateDTO.MemberState state = new ChatReadStateDTO.MemberState();
            state.setUserId(memberId);
            state.setNickname(user == null ? null
                    : (user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname()));
            state.setLastReadMessageId(member == null ? null : member.getLastReadMessageId());
            state.setLastReadAtMillis(member == null ? null : toMillis(member.getLastReadAt()));
            states.add(state);
        }
        ChatReadStateDTO dto = new ChatReadStateDTO();
        dto.setConversationId(conversationId);
        dto.setMembers(states);
        return dto;
    }

    /**
     * task-136 会话能力：置顶（每人最多 20 个）/免打扰，二者都是成员维度设置。
     * 未传的字段保持原值；都没有传 → 400。
     */
    @Transactional
    public ChatConversationDTO patchConversation(Long conversationId, Long userId, Boolean pinned, Boolean muted) {
        ensureParticipant(conversationId, userId);
        if (pinned == null && muted == null) {
            throw new BusinessException(400, "至少提供 pinned 或 muted");
        }
        ChatMember member = memberMapper.select(conversationId, userId);
        boolean currentlyPinned = member != null && Boolean.TRUE.equals(member.getPinned());
        if (Boolean.TRUE.equals(pinned) && !currentlyPinned && memberMapper.countPinned(userId) >= PINNED_MAX) {
            throw new BusinessException(400, "置顶会话最多 " + PINNED_MAX + " 个");
        }
        if (pinned != null) {
            memberMapper.updatePinned(conversationId, userId, pinned);
        }
        if (muted != null) {
            memberMapper.updateMuted(conversationId, userId, muted);
        }
        return loadConversation(userId, conversationId);
    }

    /**
     * task-136 消息搜索：会话内未删除、未撤回的文本消息，按 body 前缀匹配（大小写不敏感）。
     * 关键词为空白/超长 → 400；LIKE 特殊字符（%、_、\）先转义，参数化绑定不进 SQL 拼接。
     */
    public List<ChatSearchItemDTO> searchMessages(Long conversationId, Long userId, String q, Integer limit) {
        ensureParticipant(conversationId, userId);
        if (q == null || q.isBlank()) {
            throw new BusinessException(400, "搜索关键词不能为空");
        }
        String query = q.trim();
        if (query.length() > SEARCH_MAX_QUERY_CHARS) {
            throw new BusinessException(400, "搜索关键词过长（上限 " + SEARCH_MAX_QUERY_CHARS + " 字）");
        }
        int size = limit == null || limit <= 0 ? SEARCH_DEFAULT_LIMIT : Math.min(limit, SEARCH_MAX_LIMIT);
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<ChatMessage> messages = messageMapper.search(conversationId, escaped + "%", size);
        Set<Long> senderIds = messages.stream().map(ChatMessage::getSenderId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> users = senderIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(senderIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        List<ChatSearchItemDTO> items = new ArrayList<>();
        for (ChatMessage message : messages) {
            User sender = users.get(message.getSenderId());
            ChatSearchItemDTO item = new ChatSearchItemDTO();
            item.setId(message.getId());
            item.setBody(message.getBody());
            item.setCreatedAtMillis(toMillis(message.getCreatedAt()));
            item.setSenderNickname(sender == null ? null
                    : (sender.getNickname() == null || sender.getNickname().isBlank() ? sender.getUsername() : sender.getNickname()));
            items.add(item);
        }
        return items;
    }

    /** task-136 未读聚合：一次返回总数与按会话明细，供客户端侧栏徽标刷新。 */
    public ChatUnreadDTO unreadSummary(Long userId) {
        ensureWorkspaceChannels(userId);
        List<ChatConversationRow> rows = conversationMapper.selectForUser(userId);
        List<ChatUnreadDTO.ConversationUnread> items = new ArrayList<>();
        long total = 0L;
        for (ChatConversationRow row : rows) {
            if (row.getUnread() <= 0L) {
                continue;
            }
            total += row.getUnread();
            ChatUnreadDTO.ConversationUnread item = new ChatUnreadDTO.ConversationUnread();
            item.setId(row.getId());
            item.setUnread(row.getUnread());
            items.add(item);
        }
        ChatUnreadDTO dto = new ChatUnreadDTO();
        dto.setTotal(total);
        dto.setByConversation(items);
        return dto;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 工作区频道是懒创建的：用户首次调用会话列表时，为自己所在的每个工作区补一个 channel。
     * 并发下由 ux_chat_ws_channel 唯一索引兜底。
     */
    private void ensureWorkspaceChannels(Long userId) {
        // task-55 懒清理：删除旧版本遗留的非团队工作区频道（个人空间 channel）。
        // 理由见 ChatConversationMapper#deleteNonTeamChannels 的注释（不新增启动组件、幂等）。
        try {
            conversationMapper.deleteNonTeamChannels();
        } catch (Exception e) {
            // 清理是尽力而为：失败不能拖垮会话列表本身（频道仍会被 listConversations 过滤掉）
            log.warn("failed to clean up non-team chat channels: {}", e.getMessage());
        }
        List<WorkspaceMember> memberships = workspaceMemberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        if (memberships.isEmpty()) {
            return;
        }
        Set<Long> workspaceIds = memberships.stream()
                .map(WorkspaceMember::getWorkspaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (workspaceIds.isEmpty()) {
            return;
        }
        // 只给团队空间建频道：个人空间（type=personal）不是团队空间，不能变成“自聊页”。
        Map<Long, Workspace> workspaces = workspaceMapper.selectBatchIds(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, ws -> ws, (a, b) -> a));
        for (WorkspaceMember membership : memberships) {
            Long workspaceId = membership.getWorkspaceId();
            if (workspaceId == null) {
                continue;
            }
            Workspace workspace = workspaces.get(workspaceId);
            if (workspace == null || !WorkspaceType.TEAM.getValue().equals(workspace.getType())) {
                continue;
            }
            if (conversationMapper.selectWorkspace(workspaceId) != null) {
                continue;
            }
            ChatConversation channel = new ChatConversation();
            channel.setType(TYPE_WORKSPACE);
            channel.setWorkspaceId(workspaceId);
            channel.setCreatedBy(userId);
            try {
                conversationMapper.insert(channel);
            } catch (DuplicateKeyException ignored) {
                // 其它成员并发创建了同一频道：唯一索引兜底，属正常幂等；其它异常一律向上抛（不吞）
            }
        }
    }

    private ChatConversation ensureParticipant(Long conversationId, Long userId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (TYPE_DIRECT.equals(conversation.getType())) {
            if (memberMapper.select(conversationId, userId) == null) {
                throw new BusinessException(403, "无权访问该会话");
            }
        } else {
            Long workspaceId = conversation.getWorkspaceId();
            Long count = workspaceMemberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
                    .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                    .eq(WorkspaceMember::getUserId, userId));
            if (count == null || count == 0L) {
                throw new BusinessException(403, "无权访问该会话");
            }
            // 首次打开频道：位点从当前最大消息 id 开始，避免一进频道 999+
            if (memberMapper.select(conversationId, userId) == null) {
                insertMember(conversationId, userId, messageMapper.maxId(conversationId));
            }
        }
        return conversation;
    }

    private void insertMember(Long conversationId, Long userId, long lastReadMessageId) {
        ChatMember member = new ChatMember();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setLastReadMessageId(lastReadMessageId);
        memberMapper.insertIgnore(member);
    }

    private WorkspaceFile requireChatFile(ChatConversation conversation, Long userId, Long fileId) {
        if (fileId == null) {
            throw new BusinessException(400, "fileId 必填");
        }
        WorkspaceFile file = workspaceFileMapper.selectById(fileId);
        if (file == null || file.getDeletedAt() != null) {
            throw new BusinessException(400, "文件不存在或已删除");
        }
        Long allowedWorkspace = TYPE_WORKSPACE.equals(conversation.getType())
                ? conversation.getWorkspaceId()
                : personalSpaceService.resolveWorkspaceId(userId);
        if (!Objects.equals(allowedWorkspace, file.getWorkspaceId())) {
            throw new BusinessException(400, "文件不在该会话可访问的空间内");
        }
        return file;
    }

    private List<Long> participantIds(ChatConversation conversation) {
        if (TYPE_DIRECT.equals(conversation.getType())) {
            return memberMapper.listUserIds(conversation.getId());
        }
        return memberMapper.listWorkspaceUserIds(conversation.getWorkspaceId());
    }

    private ChatConversationDTO loadConversation(Long userId, Long conversationId) {
        return conversationMapper.selectForUser(userId).stream()
                .filter(row -> conversationId.equals(row.getId()))
                .findFirst()
                .map(this::toConversationDto)
                .orElseThrow(() -> new BusinessException(500, "会话读取失败"));
    }

    private ChatConversationDTO toConversationDto(ChatConversationRow row) {
        ChatConversationDTO dto = new ChatConversationDTO();
        dto.setId(row.getId());
        dto.setType(row.getType());
        dto.setWorkspaceId(row.getWorkspaceId());
        dto.setPeerUserId(row.getPeerUserId());
        dto.setPeerNickname(row.getPeerNickname());
        dto.setPeerAvatarUrl(row.getPeerAvatarUrl());
        dto.setWorkspaceName(row.getWorkspaceName());
        dto.setLastMessageId(row.getLastMessageId());
        dto.setLastMessageKind(row.getLastMessageKind());
        dto.setLastMessagePreview(row.getLastMessagePreview());
        dto.setLastMessageAtMillis(toMillis(row.getLastMessageAt()));
        dto.setUnreadCount(row.getUnread());
        dto.setUpdatedAtMillis(toMillis(row.getUpdatedAt()));
        dto.setPinned(row.isPinned());
        dto.setMuted(row.isMuted());
        return dto;
    }

    private List<ChatMessageDTO> enrich(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> senderIds = messages.stream().map(ChatMessage::getSenderId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> fileIds = messages.stream().map(ChatMessage::getFileId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> users = userMapper.selectBatchIds(senderIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        Map<Long, WorkspaceFile> files = fileIds.isEmpty() ? Map.of()
                : workspaceFileMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(WorkspaceFile::getId, file -> file));
        return messages.stream()
                .map(message -> toMessageDto(message, users.get(message.getSenderId()),
                        message.getFileId() == null ? null : files.get(message.getFileId())))
                .collect(Collectors.toList());
    }

    private ChatMessageDTO toMessageDto(ChatMessage message) {
        User sender = userMapper.selectById(message.getSenderId());
        WorkspaceFile file = message.getFileId() == null ? null : workspaceFileMapper.selectById(message.getFileId());
        return toMessageDto(message, sender, file);
    }

    private ChatMessageDTO toMessageDto(ChatMessage message, User sender, WorkspaceFile file) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setId(message.getId());
        dto.setConversationId(message.getConversationId());
        dto.setSenderId(message.getSenderId());
        dto.setSenderNickname(sender == null ? null : sender.getNickname());
        dto.setKind(message.getKind());
        dto.setClientMsgId(message.getClientMsgId());
        dto.setCreatedAtMillis(toMillis(message.getCreatedAt()));
        boolean deleted = message.getDeletedAt() != null;
        boolean recalled = Boolean.TRUE.equals(message.getRecalled());
        dto.setDeleted(deleted);
        dto.setRecalled(recalled);
        dto.setRecalledAtMillis(toMillis(message.getRecalledAt()));
        dto.setBody(deleted || recalled ? null : message.getBody());
        dto.setFileId(recalled ? null : message.getFileId());
        if (file != null) {
            dto.setFileName(file.getFileName());
            dto.setFileSize(file.getFileSize());
            dto.setFileContentType(file.getContentType());
        }
        return dto;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private static Long toMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }

    private static String directKey(Long userId1, Long userId2) {
        long low = Math.min(userId1, userId2);
        long high = Math.max(userId1, userId2);
        return low + ":" + high;
    }

    private boolean areFriends(Long userId1, Long userId2) {
        return friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getStatus, 1)
                .and(w -> w
                        .nested(n -> n.eq(Friendship::getRequesterId, userId1).eq(Friendship::getAddresseeId, userId2))
                        .or()
                        .nested(n -> n.eq(Friendship::getRequesterId, userId2).eq(Friendship::getAddresseeId, userId1)))) > 0;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
