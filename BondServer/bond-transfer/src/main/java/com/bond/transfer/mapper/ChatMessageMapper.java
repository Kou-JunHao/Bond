package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_messages WHERE conversation_id = #{conversationId} "
            + "AND client_msg_id = #{clientMsgId} LIMIT 1")
    ChatMessage selectByClientMsgId(@Param("conversationId") Long conversationId,
                                    @Param("clientMsgId") String clientMsgId);

    @Select("SELECT * FROM chat_messages WHERE conversation_id = #{conversationId} "
            + "AND deleted_at IS NULL ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessage> selectLatest(@Param("conversationId") Long conversationId, @Param("limit") int limit);

    @Select("SELECT * FROM chat_messages WHERE conversation_id = #{conversationId} "
            + "AND deleted_at IS NULL AND id < #{beforeId} ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessage> selectBefore(@Param("conversationId") Long conversationId,
                                   @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("SELECT * FROM chat_messages WHERE conversation_id = #{conversationId} "
            + "AND deleted_at IS NULL AND id > #{afterId} ORDER BY id ASC LIMIT #{limit}")
    List<ChatMessage> selectAfter(@Param("conversationId") Long conversationId,
                                  @Param("afterId") Long afterId, @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(id), 0) FROM chat_messages WHERE conversation_id = #{conversationId}")
    long maxId(@Param("conversationId") Long conversationId);

    @Update("UPDATE chat_messages SET deleted_at = #{deletedAt} WHERE id = #{id} AND deleted_at IS NULL")
    int softDelete(@Param("id") Long id, @Param("deletedAt") OffsetDateTime deletedAt);

    @Select("SELECT * FROM chat_messages WHERE id = #{id}")
    ChatMessage selectOneById(@Param("id") Long id);

    /**
     * task-136 撤回：正文与文件引用清空，占位保留；已撤回则影响 0 行（幂等由 service 处理）。
     */
    @Update("UPDATE chat_messages SET recalled = TRUE, recalled_at = #{recalledAt}, body = NULL, file_id = NULL "
            + "WHERE id = #{id} AND recalled = FALSE")
    int recall(@Param("id") Long id, @Param("recalledAt") OffsetDateTime recalledAt);

    /**
     * task-136 消息搜索：会话内未删除、未撤回的文本消息，按 body 前缀匹配（q%，大小写不敏感）。
     * 参数在 Mapper 层用 #{} 绑定，SQL 不做字符串拼接。
     */
    @Select("SELECT * FROM chat_messages WHERE conversation_id = #{conversationId} "
            + "AND deleted_at IS NULL AND recalled = FALSE AND kind = 'text' "
            + "AND body ILIKE #{pattern} ESCAPE '\\' ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessage> search(@Param("conversationId") Long conversationId,
                             @Param("pattern") String pattern, @Param("limit") int limit);
}
