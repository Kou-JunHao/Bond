package com.bond.common.email;

/**
 * 邮件模板读取 SPI（task-63）：返回 null 表示 DB 中没有该模板，调用方回退内置默认。
 */
public interface EmailTemplateStore {
    com.bond.common.entity.EmailTemplate find(String code);
}
