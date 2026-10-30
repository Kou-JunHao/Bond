package com.bond.auth.service;

import com.bond.auth.mapper.AdminLoginBlockMapper;
import com.bond.auth.mapper.AdminLoginLogMapper;
import com.bond.common.entity.AdminLoginBlock;
import com.bond.common.entity.AdminLoginLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 管理端登录审计的“独立事务”写入器（task-144）。
 *
 * <p>审计/封禁写入绝不能影响登录主流程：两个方法都使用 {@link Propagation#REQUIRES_NEW}
 * 开独立事务，且内部捕获运行时异常只告警；即使写库失败，调用方仍按原逻辑返回。</p>
 *
 * <p>注意：这里只写元数据（用户名/IP/UA/结果），<strong>绝不记录密码</strong>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLoginAuditWriter {

    private final AdminLoginLogMapper logMapper;
    private final AdminLoginBlockMapper blockMapper;

    /** 写一条登录审计日志（不影响登录主流程）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void writeLog(AdminLoginLog row) {
        try {
            logMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("admin login log write failed: outcome={}, ip={}, error={}",
                    row.getOutcome(), row.getIp(), e.toString());
        }
    }

    /**
     * 新建一条封禁记录：先把同 scope+value 的过期未解除行标记为已解除（规避部分唯一索引），再插入。
     *
     * @return true=新建成功；false=并发下已存在 active 封禁或写库失败（均不影响登录）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean createBlock(AdminLoginBlock block) {
        try {
            OffsetDateTime now = block.getBannedAt() == null ? OffsetDateTime.now() : block.getBannedAt();
            blockMapper.liftExpired(block.getScope(), block.getValue(), now);
            blockMapper.insert(block);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发下已有 active 封禁，视为成功
            return false;
        } catch (RuntimeException e) {
            log.warn("admin login block write failed: scope={}, value={}, error={}",
                    block.getScope(), block.getValue(), e.toString());
            return false;
        }
    }
}
