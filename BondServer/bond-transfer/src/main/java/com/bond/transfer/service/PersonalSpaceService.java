package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.service.PersonalSpaceSupport;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.entitlement.EntitlementResolver;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 个人空间解析 / 创建服务。
 *
 * <p>个人空间 = {@code workspaces} 表中 {@code type='personal'} 的一行，每个用户恰好一个。
 * 客户端不需要知道 workspaceId：{@code /api/files/**} 控制器用当前登录用户的 X-User-Id
 * 调用 {@link #resolveWorkspaceId(Long)}，再委托给 {@link WorkspaceService} 的同名方法。</p>
 *
 * <p>幂等与并发：V2 的 {@code ux_workspaces_personal_owner} 部分唯一索引兜底，
 * 插入撞唯一键时回查已有行；OWNER 成员行缺失时自动补齐（自愈）。</p>
 */
@Service
@RequiredArgsConstructor
public class PersonalSpaceService {

    private static final Logger log = LoggerFactory.getLogger(PersonalSpaceService.class);

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final ConfigService configService;
    private final EntitlementResolver entitlementResolver;

    /** 解析结果：workspaceId 为个人空间 id，created 表示本次调用是否新建。 */
    public record EnsureResult(Long workspaceId, boolean created) {
    }

    /** 按用户解析个人空间 id；不存在则即时创建（懒创建）。 */
    public Long resolveWorkspaceId(Long userId) {
        return ensurePersonalSpace(userId).workspaceId();
    }

    /** 幂等 ensure：已存在则不重复创建。 */
    public EnsureResult ensurePersonalSpace(Long userId) {
        Workspace existing = findPersonalWorkspace(userId);
        if (existing == null) {
            Workspace created = insertPersonalWorkspace(userId);
            if (created != null) {
                ensureOwnerMember(created.getId(), userId);
                return new EnsureResult(created.getId(), true);
            }
            // 并发下另一线程/进程先插入成功：回查赢家
            existing = findPersonalWorkspace(userId);
            if (existing == null) {
                throw new IllegalStateException("failed to create personal space for user " + userId);
            }
        }
        ensureOwnerMember(existing.getId(), userId);
        return new EnsureResult(existing.getId(), false);
    }

    /**
     * 启动回填：为所有缺失个人空间的存量用户创建。
     *
     * @return 本次新建的个人空间数量
     */
    public int backfillMissingPersonalSpaces() {
        List<Workspace> existingPersonals = workspaceMapper.selectList(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getType, WorkspaceType.PERSONAL.getValue()));
        Set<Long> ownersWithPersonalSpace = existingPersonals.stream()
                .map(Workspace::getOwnerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<User> users = userMapper.selectList(null);
        int created = 0;
        for (User user : users) {
            if (user.getId() == null || ownersWithPersonalSpace.contains(user.getId())) {
                continue;
            }
            if (ensurePersonalSpace(user.getId()).created()) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Personal space backfill created {} personal space(s) for existing users", created);
        } else {
            log.info("Personal space backfill finished: all users already have a personal space");
        }
        return created;
    }

    // ── 内部实现 ──

    private Workspace findPersonalWorkspace(Long userId) {
        return workspaceMapper.selectOne(new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getOwnerId, userId)
                .eq(Workspace::getType, WorkspaceType.PERSONAL.getValue()));
    }

    /** 插入个人空间；撞部分唯一索引（并发）时返回 null，由调用方回查。 */
    private Workspace insertPersonalWorkspace(Long userId) {
        // 个人空间配额 = 用户档位存储额度（task-7 权益层）；档位表不可用时回退配置默认
        long maxSize = entitlementResolver.resolve(userId).limits().storageBytes();
        if (maxSize <= 0) {
            maxSize = configService.getLong("workspace.max_size", 5368709120L);
        }
        Workspace ws = PersonalSpaceSupport.newPersonalWorkspace(userId, maxSize);
        try {
            workspaceMapper.insert(ws);
            log.info("Created personal space: userId={}, workspaceId={}, maxSize={}", userId, ws.getId(), maxSize);
            return ws;
        } catch (DuplicateKeyException e) {
            log.info("Concurrent personal space creation detected for user {}, will reuse existing row", userId);
            return null;
        }
    }

    private void ensureOwnerMember(Long workspaceId, Long userId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
        if (count != null && count > 0) {
            return;
        }
        try {
            memberMapper.insert(PersonalSpaceSupport.newOwnerMember(workspaceId, userId));
        } catch (DuplicateKeyException ignored) {
            // 并发补齐成员行：唯一约束 (workspace_id, user_id) 兜底
        }
    }
}
