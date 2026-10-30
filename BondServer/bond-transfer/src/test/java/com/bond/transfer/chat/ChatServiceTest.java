package com.bond.transfer.chat;

import com.bond.common.dto.ChatConversationDTO;
import com.bond.common.dto.ChatMessageDTO;
import com.bond.common.dto.ChatSearchItemDTO;
import com.bond.common.dto.ChatUnreadDTO;
import com.bond.common.entity.ChatConversation;
import com.bond.common.entity.ChatMember;
import com.bond.common.entity.ChatMessage;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatService 单测（Mockito，不连库）：会话列表/建会话权限、消息分页、发送幂等与限流、文件消息空间校验、已读位点、软删广播。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final Long USER = 1077L;
    private static final Long PEER = 2048L;
    private static final Long CONV = 900L;
    private static final Long WORKSPACE_ID = 5L;

    @Mock private ChatConversationMapper conversationMapper;
    @Mock private ChatMemberMapper memberMapper;
    @Mock private ChatMessageMapper messageMapper;
    @Mock private FriendshipMapper friendshipMapper;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceFileMapper workspaceFileMapper;
    @Mock private WorkspaceMemberMapper workspaceMemberMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private PersonalSpaceService personalSpaceService;
    @Mock private ConfigService configService;
    @Mock private ChatRateLimiter rateLimiter;
    @Mock private ChatWsHub wsHub;

    @InjectMocks private ChatService chatService;

    // ---------------------------------------------------------------- 会话列表

    @Test
    @DisplayName("会话列表：映射对端/摘要/未读数，时间转 epoch millis")
    void listConversationsMapsFieldsAndUnread() {
        ChatConversationRow row = new ChatConversationRow();
        row.setId(CONV);
        row.setType("direct");
        row.setPeerUserId(PEER);
        row.setPeerNickname("张三");
        row.setLastMessageId(321L);
        row.setLastMessageKind("text");
        row.setLastMessagePreview("hi");
        row.setLastMessageAt(OffsetDateTime.parse("2026-10-23T08:00:00+08:00"));
        row.setUpdatedAt(OffsetDateTime.parse("2026-10-23T08:00:01+08:00"));
        row.setUnread(2L);
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(row));

        List<ChatConversationDTO> dtoList = chatService.listConversations(USER);

        assertEquals(1, dtoList.size());
        ChatConversationDTO dto = dtoList.get(0);
        assertEquals(CONV, dto.getId());
        assertEquals("direct", dto.getType());
        assertEquals(PEER, dto.getPeerUserId());
        assertEquals("张三", dto.getPeerNickname());
        assertEquals("hi", dto.getLastMessagePreview());
        assertEquals(2L, dto.getUnreadCount());
        assertEquals(OffsetDateTime.parse("2026-10-23T08:00:00+08:00").toInstant().toEpochMilli(),
                dto.getLastMessageAtMillis());
    }

    @Test
    @DisplayName("会话列表：缺工作区频道时懒创建 chat_conversations（仅 team 空间）")
    void listConversationsCreatesMissingWorkspaceChannels() {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(WORKSPACE_ID);
        membership.setUserId(USER);
        when(workspaceMemberMapper.selectList(any())).thenReturn(List.of(membership));
        when(workspaceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(workspace(WORKSPACE_ID, WorkspaceType.TEAM)));
        when(conversationMapper.selectWorkspace(WORKSPACE_ID)).thenReturn(null);
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of());

        chatService.listConversations(USER);

        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertEquals("workspace", captor.getValue().getType());
        assertEquals(WORKSPACE_ID, captor.getValue().getWorkspaceId());
    }

    @Test
    @DisplayName("会话列表：个人空间（type=personal）不创建聊天频道")
    void listConversationsSkipsPersonalWorkspaceChannels() {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(WORKSPACE_ID);
        membership.setUserId(USER);
        when(workspaceMemberMapper.selectList(any())).thenReturn(List.of(membership));
        when(workspaceMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(workspace(WORKSPACE_ID, WorkspaceType.PERSONAL)));
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of());

        chatService.listConversations(USER);

        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    @Test
    @DisplayName("会话列表：历史 personal 频道被过滤掉（只剩 direct）")
    void listConversationsFiltersHistoricalPersonalChannel() {
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(
                row(1L, "direct", null), row(2L, "workspace", WORKSPACE_ID)));
        when(workspaceMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(workspace(WORKSPACE_ID, WorkspaceType.PERSONAL)));

        List<ChatConversationDTO> dtoList = chatService.listConversations(USER);

        assertEquals(1, dtoList.size());
        assertEquals("direct", dtoList.get(0).getType());
    }

    @Test
    @DisplayName("会话列表：TEAM 频道正常返回")
    void listConversationsKeepsTeamChannel() {
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(
                row(1L, "direct", null), row(2L, "workspace", WORKSPACE_ID)));
        when(workspaceMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(workspace(WORKSPACE_ID, WorkspaceType.TEAM)));

        List<ChatConversationDTO> dtoList = chatService.listConversations(USER);

        assertEquals(2, dtoList.size());
        assertEquals("workspace", dtoList.get(1).getType());
    }

    @Test
    @DisplayName("会话列表：工作区已删除（频道残留）时直接丢弃，不报错")
    void listConversationsDropsChannelOfDeletedWorkspace() {
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(
                row(2L, "workspace", WORKSPACE_ID)));
        when(workspaceMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        assertTrue(chatService.listConversations(USER).isEmpty());
    }

    @Test
    @DisplayName("会话列表：懒清理会删除历史非团队频道（幂等 SQL）")
    void listConversationsCleansNonTeamChannels() {
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of());

        chatService.listConversations(USER);

        verify(conversationMapper, times(1)).deleteNonTeamChannels();
    }

    private static ChatConversationRow row(Long id, String type, Long workspaceId) {
        ChatConversationRow row = new ChatConversationRow();
        row.setId(id);
        row.setType(type);
        row.setWorkspaceId(workspaceId);
        return row;
    }

    // ---------------------------------------------------------------- 建会话

    @Test
    @DisplayName("建 1:1：不能和自己聊")
    void createDirectRejectsSelf() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.createOrGetDirect(USER, USER));
        assertTrue(e.getMessage().contains("peerUserId"));
    }

    @Test
    @DisplayName("建 1:1：非好友 403")
    void createDirectRejectsNonFriend() {
        when(userMapper.selectById(PEER)).thenReturn(user(PEER, "张三"));
        when(friendshipMapper.selectCount(any())).thenReturn(0L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.createOrGetDirect(USER, PEER));
        assertTrue(e.getMessage().contains("好友"));
    }

    @Test
    @DisplayName("建 1:1：新建会话并写入双方 chat_members")
    void createDirectCreatesConversationAndBothMembers() {
        when(userMapper.selectById(PEER)).thenReturn(user(PEER, "张三"));
        when(friendshipMapper.selectCount(any())).thenReturn(1L);
        when(conversationMapper.selectDirect(USER + ":" + PEER)).thenReturn(null);
        doAnswer(inv -> {
            ChatConversation conversation = inv.getArgument(0);
            conversation.setId(CONV);
            return 1;
        }).when(conversationMapper).insert(any(ChatConversation.class));
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(directRow()));

        ChatConversationDTO dto = chatService.createOrGetDirect(USER, PEER);

        assertEquals(CONV, dto.getId());
        verify(memberMapper, times(2)).insertIgnore(any(ChatMember.class));
    }

    @Test
    @DisplayName("建 1:1：会话已存在时幂等返回，不再插入")
    void createDirectReturnsExistingConversation() {
        when(userMapper.selectById(PEER)).thenReturn(user(PEER, "张三"));
        when(friendshipMapper.selectCount(any())).thenReturn(1L);
        ChatConversation existing = new ChatConversation();
        existing.setId(CONV);
        existing.setType("direct");
        existing.setDirectKey(USER + ":" + PEER);
        when(conversationMapper.selectDirect(USER + ":" + PEER)).thenReturn(existing);
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(directRow()));

        assertEquals(CONV, chatService.createOrGetDirect(USER, PEER).getId());
        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    // ---------------------------------------------------------------- 消息分页

    @Test
    @DisplayName("消息分页：会话不存在 404")
    void listMessagesRejectsNonParticipant() {
        when(conversationMapper.selectById(CONV)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.listMessages(CONV, USER, null, null, 50));
        assertTrue(e.getMessage().contains("会话不存在"));
    }

    @Test
    @DisplayName("消息分页：direct 非 chat_members 成员 403（task-136 起不再用 404 掩盖存在性）")
    void listMessagesDirectRequiresMembership() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.listMessages(CONV, USER, null, null, 50));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("消息分页：limit 上限 100，结果按 id 升序返回")
    void listMessagesClampsLimitAndReturnsAscending() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(messageMapper.selectLatest(CONV, ChatService.MAX_PAGE_SIZE))
                .thenReturn(List.of(message(102L, "b"), message(101L, "a")));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(USER, "我")));

        List<ChatMessageDTO> messages = chatService.listMessages(CONV, USER, null, null, 500);

        verify(messageMapper).selectLatest(CONV, ChatService.MAX_PAGE_SIZE);
        assertEquals(List.of(101L, 102L), messages.stream().map(ChatMessageDTO::getId).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("消息分页：afterId 补拉保持升序")
    void listMessagesAfterIdKeepsAscendingOrder() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(messageMapper.selectAfter(CONV, 100L, 50))
                .thenReturn(List.of(message(101L, "a"), message(102L, "b")));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(USER, "我")));

        List<ChatMessageDTO> messages = chatService.listMessages(CONV, USER, null, 100L, 50);

        assertEquals(List.of(101L, 102L), messages.stream().map(ChatMessageDTO::getId).collect(Collectors.toList()));
    }

    // ---------------------------------------------------------------- 发送

    @Test
    @DisplayName("发送文本：插入消息、更新 last_message_id、向对端广播 msg 帧")
    void sendTextMessageInsertsUpdatesLastMessageAndBroadcasts() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(0L);
        doAnswer(inv -> {
            ChatMessage message = inv.getArgument(0);
            message.setId(777L);
            return 1;
        }).when(messageMapper).insert(any(ChatMessage.class));
        when(userMapper.selectById(USER)).thenReturn(user(USER, "我"));
        when(memberMapper.listUserIds(CONV)).thenReturn(List.of(USER, PEER));

        ChatMessageDTO dto = chatService.sendMessage(CONV, USER, "text", "hello", null, "c-1");

        assertEquals(777L, dto.getId());
        assertEquals("hello", dto.getBody());
        verify(conversationMapper).updateLastMessage(CONV, 777L);
        verify(wsHub).sendToUsers(eq(List.of(PEER)), eq("msg"), any(ChatMessageDTO.class));
    }

    @Test
    @DisplayName("发送限流：429 语义，且不落库")
    void sendMessageRejectsWhenRateLimited() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(42L);

        RateLimitedException e = assertThrows(RateLimitedException.class,
                () -> chatService.sendMessage(CONV, USER, "text", "hi", null, null));
        assertEquals(42L, e.getRetryAfterSeconds());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    @DisplayName("发送文本：超过正文上限 400")
    void sendTextMessageRejectsTooLong() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(0L);
        when(configService.getInt(anyString(), anyInt())).thenReturn(5);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.sendMessage(CONV, USER, "text", "123456", null, null));
        assertTrue(e.getMessage().contains("消息过长"));
    }

    @Test
    @DisplayName("发送文本：空白正文 400")
    void sendTextMessageRejectsBlankBody() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(0L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.sendMessage(CONV, USER, "text", "   ", null, null));
        assertTrue(e.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("发送幂等：同 clientMsgId 重放返回同一行，不再插入")
    void sendMessageIsIdempotentByClientMsgId() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(0L);
        when(messageMapper.selectByClientMsgId(CONV, "dup")).thenReturn(message(500L, "hello"));
        when(userMapper.selectById(USER)).thenReturn(user(USER, "我"));

        ChatMessageDTO dto = chatService.sendMessage(CONV, USER, "text", "hello", null, "dup");

        assertEquals(500L, dto.getId());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    @DisplayName("文件消息：工作区频道引用其它工作区文件 400")
    void sendFileMessageRejectsFileFromAnotherWorkspace() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONV);
        conversation.setType("workspace");
        conversation.setWorkspaceId(WORKSPACE_ID);
        when(conversationMapper.selectById(CONV)).thenReturn(conversation);
        when(workspaceMemberMapper.selectCount(any())).thenReturn(1L);
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(0L);
        WorkspaceFile foreign = new WorkspaceFile();
        foreign.setId(66L);
        foreign.setWorkspaceId(WORKSPACE_ID + 1);
        when(workspaceFileMapper.selectById(66L)).thenReturn(foreign);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.sendMessage(CONV, USER, "file", null, 66L, null));
        assertTrue(e.getMessage().contains("不在该会话可访问"));
    }

    @Test
    @DisplayName("文件消息：1:1 中引用发送者个人空间文件合法")
    void sendFileMessageAcceptsPersonalSpaceFileInDirectChat() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(rateLimiter.acquire(USER)).thenReturn(0L);
        WorkspaceFile file = new WorkspaceFile();
        file.setId(66L);
        file.setWorkspaceId(3L);
        file.setFileName("a.pdf");
        file.setFileSize(10L);
        when(workspaceFileMapper.selectById(66L)).thenReturn(file);
        when(personalSpaceService.resolveWorkspaceId(USER)).thenReturn(3L);
        doAnswer(inv -> {
            ChatMessage message = inv.getArgument(0);
            message.setId(778L);
            return 1;
        }).when(messageMapper).insert(any(ChatMessage.class));
        when(userMapper.selectById(USER)).thenReturn(user(USER, "我"));
        when(memberMapper.listUserIds(CONV)).thenReturn(List.of(USER, PEER));

        ChatMessageDTO dto = chatService.sendMessage(CONV, USER, "file", null, 66L, null);

        assertEquals("file", dto.getKind());
        assertEquals("a.pdf", dto.getFileName());
        assertNotNull(dto.getFileId());
    }

    // ---------------------------------------------------------------- 已读 / 删除

    @Test
    @DisplayName("已读：位点截断到当前最大消息 id，并向双方广播 read 帧")
    void markReadClampsToMaxMessageIdAndBroadcasts() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(messageMapper.maxId(CONV)).thenReturn(100L);
        when(memberMapper.listUserIds(CONV)).thenReturn(List.of(USER, PEER));

        chatService.markRead(CONV, USER, 999L);

        verify(memberMapper).upsertRead(CONV, USER, 100L);
        verify(wsHub).sendToUsers(eq(List.of(USER, PEER)), eq("read"), any());
    }

    @Test
    @DisplayName("删除消息：非本人 403")
    void deleteMessageRejectsOthersMessage() {
        ChatMessage message = message(300L, "hi");
        message.setSenderId(PEER);
        when(messageMapper.selectById(300L)).thenReturn(message);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.deleteMessage(300L, USER));
        assertTrue(e.getMessage().contains("只能删除自己"));
    }

    @Test
    @DisplayName("删除消息：软删并广播软删帧")
    void deleteMessageSoftDeletesAndBroadcasts() {
        ChatMessage message = message(300L, "hi");
        message.setSenderId(USER);
        when(messageMapper.selectById(300L)).thenReturn(message);
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.listUserIds(CONV)).thenReturn(List.of(USER, PEER));

        chatService.deleteMessage(300L, USER);

        verify(messageMapper).softDelete(eq(300L), any());
        verify(wsHub).sendToUsers(eq(List.of(USER, PEER)), eq("msg"), any());
    }

    // ---------------------------------------------------------------- task-136 增强

    @Test
    @DisplayName("撤回：本人 2 分钟内成功，正文/文件清空并广播占位")
    void recallMessageClearsBodyAndBroadcasts() {
        ChatMessage original = message(400L, "要撤回的话");
        original.setCreatedAt(OffsetDateTime.now().minusSeconds(5));
        original.setRecalled(false);
        ChatMessage recalled = message(400L, null);
        recalled.setRecalled(true);
        recalled.setRecalledAt(OffsetDateTime.now());
        when(messageMapper.selectOneById(400L)).thenReturn(original, recalled);
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(memberMapper.listUserIds(CONV)).thenReturn(List.of(USER, PEER));

        ChatMessageDTO dto = chatService.recallMessage(400L, USER);

        verify(messageMapper).recall(eq(400L), any());
        assertTrue(dto.isRecalled());
        assertEquals(null, dto.getBody());
        assertEquals(null, dto.getFileId());
        verify(wsHub).sendToUsers(eq(List.of(USER, PEER)), eq("msg"), any());
    }

    @Test
    @DisplayName("撤回：超过 2 分钟 → 400")
    void recallMessageRejectsAfterWindow() {
        ChatMessage old = message(401L, "太久以前");
        old.setCreatedAt(OffsetDateTime.now().minusSeconds(121));
        when(messageMapper.selectOneById(401L)).thenReturn(old);
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.recallMessage(401L, USER));
        assertEquals(400, e.getCode());
        verify(messageMapper, never()).recall(any(), any());
    }

    @Test
    @DisplayName("撤回：非本人 → 403")
    void recallMessageRejectsOthersMessage() {
        ChatMessage other = message(402L, "别人发的");
        other.setSenderId(PEER);
        when(messageMapper.selectOneById(402L)).thenReturn(other);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.recallMessage(402L, USER));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("撤回：消息不存在/已删除 → 404；已撤回幂等不重复写")
    void recallMessageIsIdempotentAndRejectsMissing() {
        when(messageMapper.selectOneById(403L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> chatService.recallMessage(403L, USER)).getCode());

        ChatMessage already = message(404L, null);
        already.setSenderId(USER);
        already.setRecalled(true);
        when(messageMapper.selectOneById(404L)).thenReturn(already);
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        assertTrue(chatService.recallMessage(404L, USER).isRecalled());
        verify(messageMapper, never()).recall(eq(404L), any());
    }

    @Test
    @DisplayName("搜索：q 空白 → 400；正常按 body 前缀参数化匹配并带发送者昵称")
    void searchMessagesValidatesAndMapsNickname() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> chatService.searchMessages(CONV, USER, "  ", 10)).getCode());

        ChatMessage hit = message(500L, "hello world");
        when(messageMapper.search(CONV, "hello%", 10)).thenReturn(List.of(hit));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(USER, "我")));

        List<ChatSearchItemDTO> items = chatService.searchMessages(CONV, USER, "hello", 10);
        assertEquals(1, items.size());
        assertEquals("hello world", items.get(0).getBody());
        assertEquals("我", items.get(0).getSenderNickname());
    }

    @Test
    @DisplayName("置顶/免打扰：成员维度更新；置顶超过 20 → 400")
    void patchConversationPinsAndEnforcesLimit() {
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(memberMapper.countPinned(USER)).thenReturn(19L);
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(directRow()));

        ChatConversationDTO dto = chatService.patchConversation(CONV, USER, true, true);

        verify(memberMapper).updatePinned(CONV, USER, true);
        verify(memberMapper).updateMuted(CONV, USER, true);
        assertNotNull(dto);

        when(memberMapper.countPinned(USER)).thenReturn(20L);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> chatService.patchConversation(CONV, USER, true, null)).getCode());
    }

    @Test
    @DisplayName("未读聚合：只统计 unread>0 的会话并给总数")
    void unreadSummaryAggregates() {
        ChatConversationRow unreadRow = directRow();
        unreadRow.setUnread(3L);
        ChatConversationRow idleRow = directRow();
        idleRow.setId(901L);
        idleRow.setUnread(0L);
        when(workspaceMemberMapper.selectList(any())).thenReturn(List.of());
        when(conversationMapper.selectForUser(USER)).thenReturn(List.of(unreadRow, idleRow));

        ChatUnreadDTO dto = chatService.unreadSummary(USER);

        assertEquals(3L, dto.getTotal());
        assertEquals(1, dto.getByConversation().size());
        assertEquals(CONV, dto.getByConversation().get(0).getId());
    }

    @Test
    @DisplayName("图片消息：fileId 指向非图片文件 → 400；图片文件通过")
    void imageMessageRequiresImageFile() {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(700L);
        file.setWorkspaceId(11L);
        file.setContentType("text/plain");
        when(conversationMapper.selectById(CONV)).thenReturn(directConversation());
        when(memberMapper.select(CONV, USER)).thenReturn(member());
        when(personalSpaceService.resolveWorkspaceId(USER)).thenReturn(11L);
        when(workspaceFileMapper.selectById(700L)).thenReturn(file);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.sendMessage(CONV, USER, "image", null, 700L, null));
        assertEquals(400, e.getCode());

        file.setContentType("image/png");
        when(conversationMapper.updateLastMessage(any(), any())).thenReturn(1);
        when(memberMapper.listUserIds(CONV)).thenReturn(List.of(USER, PEER));
        ChatMessageDTO dto = chatService.sendMessage(CONV, USER, "image", null, 700L, null);
        assertEquals("image", dto.getKind());
        assertEquals(700L, dto.getFileId());
    }

    // ---------------------------------------------------------------- fixtures

    private static User user(Long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }

    private static ChatConversation directConversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONV);
        conversation.setType("direct");
        conversation.setDirectKey(USER + ":" + PEER);
        return conversation;
    }

    private static ChatMember member() {
        ChatMember member = new ChatMember();
        member.setConversationId(CONV);
        member.setUserId(USER);
        member.setLastReadMessageId(0L);
        return member;
    }

    private static ChatConversationRow directRow() {
        ChatConversationRow row = new ChatConversationRow();
        row.setId(CONV);
        row.setType("direct");
        row.setPeerUserId(PEER);
        row.setPeerNickname("张三");
        return row;
    }

    private static ChatMessage message(Long id, String body) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setConversationId(CONV);
        message.setSenderId(USER);
        message.setKind("text");
        message.setBody(body);
        message.setCreatedAt(OffsetDateTime.parse("2026-10-23T08:00:00+08:00"));
        return message;
    }

    private static Workspace workspace(Long id, WorkspaceType type) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setType(type.getValue());
        return workspace;
    }
}
