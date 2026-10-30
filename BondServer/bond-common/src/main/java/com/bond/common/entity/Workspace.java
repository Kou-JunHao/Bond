package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workspaces")
public class Workspace {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private Long maxSize;
    private Long usedSize;
    /** 工作区类型：team / personal，见 {@link com.bond.common.enums.WorkspaceType}。 */
    private String type;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
