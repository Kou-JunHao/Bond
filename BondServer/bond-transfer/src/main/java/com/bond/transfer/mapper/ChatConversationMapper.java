package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.ChatConversation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    @Select("SELECT * FROM chat_conversations WHERE type = 'direct' AND direct_key = #{directKey} LIMIT 1")
    ChatConversation selectDirect(@Param("directKey") String directKey);

    @Select("SELECT * FROM chat_conversations WHERE type = 'workspace' AND workspace_id = #{workspaceId} LIMIT 1")
    ChatConversation selectWorkspace(@Param("workspaceId") Long workspaceId);

    @Update("UPDATE chat_conversations SET last_message_id = GREATEST(COALESCE(last_message_id, 0), #{messageId}), "
            + "updated_at = now() WHERE id = #{id}")
    int updateLastMessage(@Param("id") Long id, @Param("messageId") Long messageId);

    /**
     * 会话列表：一次查询带对端信息/工作区名/最后消息摘要/未读数（LATERAL，避免 N+1）。
     * 没打开过的工作区频道 chat_members 行可能不存在，此时未读恒为 0（首次打开时再建位点）。
     *
     * <p>task-136：附带当前用户的 pinned/muted，并按「置顶优先 → 最后消息 id 倒序」排序；
     * 预览对文件/图片/撤回消息分别显示占位，避免把 null 或纯正文泄露给列表。</p>
     */
    @Select("SELECT c.id, c.type, c.workspace_id, c.last_message_id, c.updated_at, "
            + "e.user_id AS peer_user_id, COALESCE(u.nickname, u.username) AS peer_nickname, u.avatar_url AS peer_avatar_url, "
            + "w.name AS workspace_name, "
            + "lm.kind AS last_message_kind, "
            + "CASE WHEN lm.deleted_at IS NOT NULL THEN NULL "
            + "     WHEN lm.recalled THEN '[撤回]' "
            + "     WHEN lm.kind = 'image' THEN '[图片]' "
            + "     WHEN lm.kind = 'file' THEN '[文件]' "
            + "     ELSE left(coalesce(lm.body, ''), 200) END AS last_message_preview, "
            + "lm.created_at AS last_message_at, "
            + "COALESCE(cm.pinned, FALSE) AS pinned, COALESCE(cm.muted, FALSE) AS muted, "
            + "CASE WHEN cm.user_id IS NULL THEN 0 ELSE ("
            + "  SELECT count(*) FROM chat_messages x "
            + "   WHERE x.conversation_id = c.id AND x.id > cm.last_read_message_id "
            + "     AND x.sender_id <> #{me} AND x.deleted_at IS NULL"
            + ") END AS unread "
            + "FROM chat_conversations c "
            + "LEFT JOIN chat_members cm ON cm.conversation_id = c.id AND cm.user_id = #{me} "
            + "LEFT JOIN LATERAL ("
            + "  SELECT m2.user_id FROM chat_members m2 "
            + "   WHERE m2.conversation_id = c.id AND m2.user_id <> #{me} LIMIT 1"
            + ") e ON TRUE "
            + "LEFT JOIN users u ON u.id = e.user_id "
            + "LEFT JOIN workspaces w ON w.id = c.workspace_id "
            + "LEFT JOIN chat_messages lm ON lm.id = c.last_message_id "
            + "WHERE (c.type = 'direct' AND EXISTS ("
            + "        SELECT 1 FROM chat_members m WHERE m.conversation_id = c.id AND m.user_id = #{me})) "
            + "   OR (c.type = 'workspace' AND w.type = 'team' AND EXISTS ("
            + "        SELECT 1 FROM workspace_members wm WHERE wm.workspace_id = c.workspace_id AND wm.user_id = #{me})) "
            + "ORDER BY COALESCE(cm.pinned, FALSE) DESC, COALESCE(c.last_message_id, 0) DESC, c.updated_at DESC")
    List<ChatConversationRow> selectForUser(@Param("me") Long me);

    /**
     * task-55：清掉历史遗留的「非团队工作区频道」（个人空间 channel 是 task-54 之前的 bug 产物）。
     *
     * <p>选择在 {@code ensureWorkspaceChannels} 里懒清理，而不是新增启动 CommandLineRunner：
     * 不引入新启动组件/不依赖容器启动顺序，天然幂等（第二次起无行可删），且只影响 workspace
     * 频道、不碰 1:1 会话。生产历史行已由 Lead 手工 purge，本 SQL 只防止旧版本数据再出现。</p>
     */
    @Delete("DELETE FROM chat_conversations c USING workspaces w "
            + "WHERE c.type = 'workspace' AND c.workspace_id = w.id AND w.type <> 'team'")
    int deleteNonTeamChannels();
}
