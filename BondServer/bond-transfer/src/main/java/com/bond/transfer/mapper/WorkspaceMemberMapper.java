package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.WorkspaceMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {

    /**
     * 按工作区批量统计成员数（task-102）：一条 GROUP BY 补齐当前页，避免逐行 COUNT 的 N+1。
     */
    @Select("""
            <script>
            SELECT workspace_id, count(*) AS member_count
            FROM workspace_members
            WHERE workspace_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY workspace_id
            </script>
            """)
    List<Map<String, Object>> countByWorkspaceIds(@Param("ids") List<Long> ids);
}
