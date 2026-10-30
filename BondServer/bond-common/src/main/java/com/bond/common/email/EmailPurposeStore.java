package com.bond.common.email;

/**
 * 「用途 → 模板」绑定读取（task-76 方案 B）。
 *
 * <p>接口放在 bond-common，bond-auth 与 bond-transfer 各自用本地 Mapper 实现；
 * {@link ConfigurableEmailGateway} 只依赖本接口，因此两个服务共用同一套「绑定 → 内置默认」解析逻辑。</p>
 */
public interface EmailPurposeStore {

    /**
     * 返回该用途绑定的模板 code；未绑定返回 {@code null}。
     *
     * <p>实现不应对「表/库异常」做静默吞掉——网关会捕获异常并回退内置模板，
     * 但需要留日志便于排障。</p>
     */
    String boundTemplateCode(String purpose);
}
