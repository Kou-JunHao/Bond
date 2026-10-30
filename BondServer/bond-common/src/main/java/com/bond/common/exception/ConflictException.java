package com.bond.common.exception;

import lombok.Getter;

/**
 * 乐观锁冲突（HTTP 409）。
 *
 * <p>携带冲突时服务端的文档状态（serverVersion / lastEditorName / updatedAt），
 * 由 {@code GlobalExceptionHandler} 统一放进 {@code Result.data}，客户端据此提示
 * “保留我的（force 覆盖）”或“重新加载”。</p>
 */
@Getter
public class ConflictException extends BusinessException {

    private final Object data;

    public ConflictException(String message, Object data) {
        super(409, message);
        this.data = data;
    }
}
