package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

    /** 版本列表（不含正文，limit ≤ 100、offset ≤ 10000 由业务层钳制）。 */
    @Select("SELECT id, document_id, version, size_bytes, editor_id, created_at "
            + "FROM document_versions WHERE document_id = #{documentId} "
            + "ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}")
    List<DocumentVersion> selectPage(@Param("documentId") Long documentId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /** 合并同一人 60 秒内的自动保存：只更新该版本行的内容与体积（不新增版本）。 */
    @Update("UPDATE document_versions SET content_md = #{contentMd}, content_html = #{contentHtml}, "
            + "size_bytes = #{sizeBytes} WHERE id = #{id}")
    int updateContent(@Param("id") Long id,
                      @Param("contentMd") String contentMd,
                      @Param("contentHtml") String contentHtml,
                      @Param("sizeBytes") long sizeBytes);

    /** 最新一个版本行（自动保存 60 秒合并判定用）。 */
    @Select("SELECT * FROM document_versions WHERE document_id = #{documentId} "
            + "ORDER BY version DESC LIMIT 1")
    DocumentVersion selectLatest(@Param("documentId") Long documentId);

    /** 指定版本（含正文）。 */
    @Select("SELECT * FROM document_versions WHERE document_id = #{documentId} AND version = #{version}")
    DocumentVersion selectByVersion(@Param("documentId") Long documentId, @Param("version") long version);

    /** 版本体积元数据（按版本倒序），供保留策略计算总字节。 */
    @Select("SELECT id, version, size_bytes FROM document_versions "
            + "WHERE document_id = #{documentId} ORDER BY version DESC")
    List<DocumentVersion> selectSizes(@Param("documentId") Long documentId);

    /** 找出「版本数超限」或「历史总字节超限」的文档 id（定时清理用，单轮上限 batch）。 */
    @Select("SELECT document_id FROM document_versions GROUP BY document_id "
            + "HAVING count(*) > #{keep} OR sum(size_bytes) > #{totalBytes} LIMIT #{batch}")
    List<Long> selectBloatDocumentIds(@Param("keep") int keep,
                                      @Param("totalBytes") long totalBytes,
                                      @Param("batch") int batch);
}
