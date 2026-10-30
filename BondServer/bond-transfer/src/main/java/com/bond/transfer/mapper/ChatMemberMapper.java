package com.bond.transfer.mapper;

import com.bond.common.entity.ChatMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * chat_members 是复合主键表，MyBatis-Plus BaseMapper 不适用，全部用自定义 SQL。
 */
@Mapper
public interface ChatMemberMapper {

    String COLUMNS = "conversation_id, user_id, last_read_message_id, last_read_at, muted, pinned, joined_at";

    @Select("SELECT " + COLUMNS + " FROM chat_members "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId}")
    ChatMember select(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("SELECT " + COLUMNS + " FROM chat_members "
            + "WHERE conversation_id = #{conversationId} ORDER BY joined_at ASC, user_id ASC")
    List<ChatMember> listMembers(@Param("conversationId") Long conversationId);

    /** 首次加入（direct 建会话 / 首次打开工作区频道）：已存在则忽略。 */
    @Insert("INSERT INTO chat_members (conversation_id, user_id, last_read_message_id, muted, pinned, joined_at) "
            + "VALUES (#{conversationId}, #{userId}, #{lastReadMessageId}, FALSE, FALSE, now()) "
            + "ON CONFLICT (conversation_id, user_id) DO NOTHING")
    int insertIgnore(ChatMember member);

    /** 已读位点只增不减（GREATEST），行不存在时插入并记录 last_read_at。 */
    @Insert("INSERT INTO chat_members (conversation_id, user_id, last_read_message_id, last_read_at, muted, pinned, joined_at) "
            + "VALUES (#{conversationId}, #{userId}, #{messageId}, now(), FALSE, FALSE, now()) "
            + "ON CONFLICT (conversation_id, user_id) DO UPDATE "
            + "SET last_read_message_id = GREATEST(chat_members.last_read_message_id, EXCLUDED.last_read_message_id), "
            + "    last_read_at = now()")
    int upsertRead(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                   @Param("messageId") Long messageId);

    @Update("UPDATE chat_members SET pinned = #{pinned} "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId}")
    int updatePinned(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                     @Param("pinned") boolean pinned);

    @Update("UPDATE chat_members SET muted = #{muted} "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId}")
    int updateMuted(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                    @Param("muted") boolean muted);

    /** 当前用户置顶会话数（置顶上限 20 的校验依据）。 */
    @Select("SELECT count(*) FROM chat_members WHERE user_id = #{userId} AND pinned = TRUE")
    long countPinned(@Param("userId") Long userId);

    @Select("SELECT user_id FROM chat_members WHERE conversation_id = #{conversationId}")
    List<Long> listUserIds(@Param("conversationId") Long conversationId);

    /** 工作区频道的参与者 = 当前工作区成员（成员变动自动生效，不落 chat_members）。 */
    @Select("SELECT user_id FROM workspace_members WHERE workspace_id = #{workspaceId}")
    List<Long> listWorkspaceUserIds(@Param("workspaceId") Long workspaceId);
}
