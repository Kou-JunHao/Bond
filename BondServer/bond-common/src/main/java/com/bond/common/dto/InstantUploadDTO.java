package com.bond.common.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 秒传（instant upload）响应：与普通上传返回的 {@link WorkspaceFileDTO} 同构，
 * 额外带 {@code instant=true} 供客户端区分「秒传命中 / 普通上传」。
 *
 * <p>JSON 示例：{@code {"id":"...","fileName":"a.bin",...,"instant":true}}</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InstantUploadDTO extends WorkspaceFileDTO {

    /** 固定 true：秒传命中并已复制对象、占用配额。 */
    private Boolean instant = Boolean.TRUE;
}
