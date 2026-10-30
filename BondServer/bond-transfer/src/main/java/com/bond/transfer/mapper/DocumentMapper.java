package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /** 行锁读取（保存/恢复版本时串行化，避免并发下配额与版本号交错）。 */
    @Select("SELECT * FROM documents WHERE id = #{id} AND deleted_at IS NULL FOR UPDATE")
    Document selectForUpdate(@Param("id") Long id);
}
