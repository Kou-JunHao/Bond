package com.bond.auth.service;

import com.bond.auth.mapper.WorkspaceMapper;
import com.bond.auth.mapper.WorkspaceMemberMapper;
import com.bond.common.entity.Workspace;
import com.bond.common.service.PersonalSpaceSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 注册成功后的个人空间创建（个人空间 = workspaces 表 type='personal' 的一行）。
 *
 * <p>与 bond-transfer 的 PersonalSpaceService 使用同一套 {@link PersonalSpaceSupport} 构造逻辑；
 * 唯一差异：这里面对的是刚插入的新用户，正常不存在并发，因此直接插入，
 * 由 V2 的部分唯一索引兜底「一人一个」。</p>
 */
@Service
@RequiredArgsConstructor
public class PersonalSpaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(PersonalSpaceInitializer.class);

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final ConfigService configService;

    /** 为新注册用户创建个人空间及其 OWNER 成员行。调用方 register 在事务内执行。 */
    public Long createForUser(Long userId) {
        long maxSize = configService.getLong("workspace.max_size", 5368709120L);
        Workspace ws = PersonalSpaceSupport.newPersonalWorkspace(userId, maxSize);
        workspaceMapper.insert(ws);
        memberMapper.insert(PersonalSpaceSupport.newOwnerMember(ws.getId(), userId));
        log.info("Created personal space for new user: userId={}, workspaceId={}", userId, ws.getId());
        return ws.getId();
    }
}
