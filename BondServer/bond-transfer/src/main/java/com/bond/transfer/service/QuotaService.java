package com.bond.transfer.service;

import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 配额刷新：档位变更（升级/降级/兑换）后，把新额度同步到该用户名下所有工作区（含个人空间）。
 *
 * <p>降级导致已用空间 > 新额度时**不删除任何数据**：后续上传会被原子条件更新拒绝（只读语义）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final WorkspaceMapper workspaceMapper;
    private final EntitlementResolver entitlementResolver;

    public void refreshLimitsForUser(Long userId) {
        long storageBytes = entitlementResolver.resolve(userId).limits().storageBytes();
        int updated = workspaceMapper.updateMaxSizeByOwner(userId, storageBytes);
        log.info("Quota limits refreshed: userId={}, storageBytes={}, workspacesUpdated={}",
                userId, storageBytes, updated);
    }
}
