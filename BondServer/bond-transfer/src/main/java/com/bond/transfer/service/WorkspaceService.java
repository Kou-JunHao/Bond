package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bond.common.dto.WorkspaceDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.dto.WorkspaceMemberDTO;
import com.bond.common.dto.StorageQuotaDTO;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceInvitation;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.trash.FileTree;
import com.bond.transfer.trash.TrashRetentionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/bond-workspace";

    /** 目录行在 DB 里的 content_type（与 WebDAV 的 collection 语义一致；minio_path 落空串）。 */
    static final String CONTENT_TYPE_DIRECTORY = "httpd/unix-directory";

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceFileMapper fileMapper;
    private final WorkspaceInvitationMapper invitationMapper;
    private final UserMapper userMapper;
    private final ObjectStorage objectStorage;
    private final EntitlementResolver entitlementResolver;
    private final UsageService usageService;
    private final ConfigService configService;
    private final ThumbnailService thumbnailService;
    private final Map<String, WorkspaceUploadSession> uploadSessions = new ConcurrentHashMap<>();
    private final Map<String, Object> uploadLocks = new ConcurrentHashMap<>();

    @Transactional
    public WorkspaceDTO createWorkspace(Long userId, String name, String description) {
        // 可创建工作区数 / 空间额度都由档位决定（个人空间不计入创建数）
        Entitlements entitlements = entitlementResolver.resolve(userId);
        int maxCreate = entitlements.limits().maxWorkspaces();
        long createdCount = workspaceMapper.selectCount(
                new LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getOwnerId, userId)
                        .ne(Workspace::getType, WorkspaceType.PERSONAL.getValue()));
        if (createdCount >= maxCreate) {
            throw new BusinessException(400, "max created workspaces reached(" + maxCreate + ")");
        }

        Workspace ws = new Workspace();
        ws.setId(SnowflakeId.nextId());
        ws.setName(name);
        ws.setDescription(description);
        ws.setOwnerId(userId);
        // 团队工作区配额 = owner 档位的空间额度（成员上传只占工作区，不占成员个人空间）
        ws.setMaxSize(entitlements.limits().storageBytes());
        ws.setUsedSize(0L);
        ws.setType(WorkspaceType.TEAM.getValue());
        workspaceMapper.insert(ws);

        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(ws.getId());
        member.setUserId(userId);
        member.setRole(WorkspaceRole.OWNER.getValue());
        memberMapper.insert(member);

        return toDTO(ws, 1L, 0L);
    }

    /**
     * 工作区页只展示团队工作区：个人空间（type='personal'）必须排除，
     * 否则用户会在「工作区」里看到自己的「个人空间」。
     */
    public List<WorkspaceDTO> listWorkspaces(Long userId) {
        List<WorkspaceMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        List<Long> wsIds = memberships.stream().map(WorkspaceMember::getWorkspaceId).collect(Collectors.toList());
        if (wsIds.isEmpty()) return List.of();
        List<Workspace> workspaces = workspaceMapper.selectList(
                        new LambdaQueryWrapper<Workspace>().in(Workspace::getId, wsIds))
                .stream()
                .filter(ws -> !WorkspaceType.PERSONAL.getValue().equals(ws.getType()))
                .collect(Collectors.toList());
        if (workspaces.isEmpty()) return List.of();
        // 一次聚合查询补齐 memberCount/fileCount（不逐工作区 COUNT，避免 N+1）
        Map<Long, long[]> stats = workspaceStats(
                workspaces.stream().map(Workspace::getId).collect(Collectors.toList()));
        return workspaces.stream()
                .map(ws -> {
                    long[] counts = stats.getOrDefault(ws.getId(), ZERO_COUNTS);
                    return toDTO(ws, counts[0], counts[1]);
                })
                .collect(Collectors.toList());
    }

    public WorkspaceDTO getWorkspace(Long workspaceId, Long userId) {
        checkMember(workspaceId, userId);
        Workspace ws = getWorkspaceEntity(workspaceId);
        long[] counts = workspaceStats(List.of(workspaceId)).getOrDefault(workspaceId, ZERO_COUNTS);
        return toDTO(ws, counts[0], counts[1]);
    }

    /**
     * 配额查询（个人空间页使用）：used 来自 workspace 行，max 按档位**实时解析**
     * （个人空间配额 = 用户档位 storage_bytes，档位变更后立即反映，不读可能过期的 max_size）。
     */
    public StorageQuotaDTO getQuota(Long workspaceId, Long userId) {
        Workspace ws = getWorkspaceEntity(workspaceId);
        Long ownerId = ws.getOwnerId() != null ? ws.getOwnerId() : userId;
        return getQuota(workspaceId, userId, entitlementResolver.resolveFresh(ownerId));
    }

    /**
     * 配额查询（调用方已按档位实时解析过 {@code entitlements} 时使用，避免重复解析/重复 SQL）。
     * used 来自 workspace 行，max 来自传入的 entitlements（个人空间 = storage_bytes）。
     */
    public StorageQuotaDTO getQuota(Long workspaceId, Long userId, Entitlements entitlements) {
        checkMember(workspaceId, userId);
        Workspace ws = getWorkspaceEntity(workspaceId);
        Long fileCount = fileMapper.selectCount(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .eq(WorkspaceFile::getIsDirectory, false)
                .isNull(WorkspaceFile::getDeletedAt));

        StorageQuotaDTO dto = new StorageQuotaDTO();
        dto.setUsedBytes(ws.getUsedSize() == null ? 0L : ws.getUsedSize());
        dto.setMaxBytes(entitlements.limits().storageBytes());
        dto.setFileCount(fileCount == null ? 0L : fileCount);
        dto.setTrashBytes(workspaceMapper.selectTrashBytes(workspaceId));
        dto.setPlanCode(entitlements.planCode());
        return dto;
    }

    /**
     * 改名/改描述/改配额上限。配额上限不能超过 owner 档位空间额度，也不能低于已用空间；
     * 个人空间的配额由用户档位统一管理，不允许通过工作区 API 单独设置。
     */
    public WorkspaceDTO updateWorkspace(Long workspaceId, Long userId, String name, String description, Long maxSizeBytes) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        Workspace ws = getWorkspaceEntity(workspaceId);
        if (maxSizeBytes != null) {
            if (WorkspaceType.PERSONAL.getValue().equals(ws.getType())) {
                throw new BusinessException(400, "personal space quota is managed by the user plan");
            }
            if (maxSizeBytes <= 0) {
                throw new BusinessException(400, "invalid workspace quota: " + maxSizeBytes);
            }
            long used = ws.getUsedSize() == null ? 0L : ws.getUsedSize();
            if (maxSizeBytes < used) {
                throw new BusinessException(400,
                        "workspace quota cannot be less than used size(" + used + ")");
            }
            long planStorage = entitlementResolver.resolve(ws.getOwnerId()).limits().storageBytes();
            // 0 = 不限（与档位约定一致）；否则工作区上限不得超过 owner 档位空间额度
            if (planStorage > 0 && maxSizeBytes > planStorage) {
                throw new BusinessException(400,
                        "workspace quota exceeds owner plan storage limit(" + planStorage + ")");
            }
            ws.setMaxSize(maxSizeBytes);
        }
        if (name != null) ws.setName(name);
        if (description != null) ws.setDescription(description);
        workspaceMapper.updateById(ws);
        return toDTOWithStats(ws);
    }

    @Transactional
    public void deleteWorkspace(Long workspaceId, Long userId) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER);
        deleteWorkspaceCascade(workspaceId);
    }

    private void deleteWorkspaceCascade(Long workspaceId) {
        Workspace ws = getWorkspaceEntity(workspaceId);
        if (WorkspaceType.PERSONAL.getValue().equals(ws.getType())) {
            // 个人空间不允许通过工作区 API 删除（删了下次 /api/files 会重建，但会丢文件）
            throw new BusinessException(400, "personal space cannot be deleted");
        }
        List<WorkspaceFile> files = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId));
        for (WorkspaceFile file : files) {
            try {
                if (file.getMinioPath() != null) objectStorage.deleteObject(file.getMinioPath());
            } catch (Exception ignored) {
            }
        }
        fileMapper.delete(new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId));
        memberMapper.delete(new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        invitationMapper.delete(new LambdaQueryWrapper<WorkspaceInvitation>().eq(WorkspaceInvitation::getWorkspaceId, workspaceId));
        workspaceMapper.deleteById(workspaceId);
    }

    public void addMember(Long workspaceId, Long userId, Long targetUserId, WorkspaceRole role) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

        // 成员数上限由 owner 档位决定（免费用户可加入别人的付费工作区，不受自己档位限制）
        Workspace workspace = getWorkspaceEntity(workspaceId);
        int maxMembers = entitlementResolver.resolve(workspace.getOwnerId()).limits().maxWorkspaceMembers();
        long memberCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        if (memberCount >= maxMembers) {
            throw new BusinessException(400, "workspace member limit reached(" + maxMembers + ")");
        }

        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, targetUserId));
        if (existing != null) return;

        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(workspaceId);
        member.setUserId(targetUserId);
        member.setRole(role.getValue());
        memberMapper.insert(member);
    }

    @Transactional
    public void removeMember(Long workspaceId, Long userId, Long targetUserId) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        Workspace ws = getWorkspaceEntity(workspaceId);
        // 空间所有者不可被移除；删除空间请用 DELETE /api/workspaces/{id}
        if (targetUserId != null && targetUserId.equals(ws.getOwnerId())) {
            throw new BusinessException(400, "cannot remove workspace owner; delete the workspace instead");
        }

        memberMapper.delete(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, targetUserId));
    }

    /**
     * 修改成员角色。规则：
     * - 只有 OWNER/ADMIN 能调用；owner 角色不可变更，也不能把任何人提升为 owner（空间唯一所有者）；
     * - 不能修改自己的角色，避免管理员把最后一个管理员降级导致空间失控；
     * - ADMIN 只能管理普通成员；涉及 ADMIN 的任免只有 OWNER 可以操作（避免提权）。
     */
    public void updateMemberRole(Long workspaceId, Long operatorId, Long targetUserId, Integer role) {
        WorkspaceMember operator = requireRole(workspaceId, operatorId,
                WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        if (role == null) {
            throw new BusinessException(400, "role is required");
        }
        WorkspaceRole newRole;
        if (role == WorkspaceRole.ADMIN.getValue()) {
            newRole = WorkspaceRole.ADMIN;
        } else if (role == WorkspaceRole.MEMBER.getValue()) {
            newRole = WorkspaceRole.MEMBER;
        } else {
            throw new BusinessException(400, "invalid role: " + role + " (owner is not assignable)");
        }
        Workspace ws = getWorkspaceEntity(workspaceId);
        if (ws.getOwnerId().equals(targetUserId)) {
            throw new BusinessException(400, "cannot change workspace owner role");
        }
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException(400, "cannot change own role");
        }
        WorkspaceMember target = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, targetUserId));
        if (target == null) {
            throw new BusinessException(404, "workspace member not found");
        }
        boolean operatorIsOwner = operator.getRole().equals(WorkspaceRole.OWNER.getValue());
        boolean targetIsAdmin = target.getRole().equals(WorkspaceRole.ADMIN.getValue());
        if (!operatorIsOwner && (targetIsAdmin || newRole == WorkspaceRole.ADMIN)) {
            throw new BusinessException(403, "only workspace owner can manage admin roles");
        }
        target.setRole(newRole.getValue());
        memberMapper.updateById(target);
    }

    public List<WorkspaceMemberDTO> listMembers(Long workspaceId, Long userId) {
        checkMember(workspaceId, userId);
        return memberMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId))
                .stream()
                .map(this::toMemberDTO)
                .collect(Collectors.toList());
    }

    public List<WorkspaceFileDTO> listFiles(Long workspaceId, Long userId, String parentPath) {
        checkMember(workspaceId, userId);
        String path = normalizePath(parentPath);
        return fileMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceFile>()
                                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                                .eq(WorkspaceFile::getParentPath, path)
                                .isNull(WorkspaceFile::getDeletedAt)
                                .orderByAsc(WorkspaceFile::getIsDirectory)
                                .orderByAsc(WorkspaceFile::getFileName))
                .stream()
                .map(this::toFileDTO)
                .collect(Collectors.toList());
    }

    public WorkspaceFileDTO checkHash(Long workspaceId, Long userId, String hash, String fileName, Long fileSize, String parentPath) {
        checkMember(workspaceId, userId);
        if (hash == null || hash.isBlank() || fileSize == null) return null;

        String path = normalizePath(parentPath);
        List<WorkspaceFile> hashMatches = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>()
                        .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                        .eq(WorkspaceFile::getFileHash, hash)
                        .eq(WorkspaceFile::getIsDirectory, false)
                        .isNull(WorkspaceFile::getDeletedAt));
        if (hashMatches.isEmpty()) return null;

        WorkspaceFile match = hashMatches.stream()
                .filter(f -> fileSize.equals(f.getFileSize()))
                .findFirst()
                .orElse(null);
        if (match == null) return null;

        List<WorkspaceFile> sameName = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>()
                        .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                        .eq(WorkspaceFile::getParentPath, path)
                        .eq(WorkspaceFile::getFileName, fileName)
                        .eq(WorkspaceFile::getIsDirectory, false)
                        .isNull(WorkspaceFile::getDeletedAt));

        for (WorkspaceFile f : sameName) {
            if (fileSize.equals(f.getFileSize()) && hash.equals(f.getFileHash())) {
                return toFileDTO(f);
            }
        }

        validateUploadLimits(workspaceId, userId, fileSize);

        // 原子占用逻辑空间（并发安全）：影响行数 0 即超额，只读不删
        reserveStorage(workspaceId, fileSize);
        String finalName = resolveFileName(workspaceId, path, fileName);
        // task-41：不能直接复用源行的 minioPath（两行共享同一对象，删其一会让另一个 404），
        // 改为 S3 server-side CopyObject 出独立对象，语义与 /instant-upload 一致。
        String copiedKey = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + finalName;
        try {
            objectStorage.copyObject(match.getMinioPath(), copiedKey);
            WorkspaceFile wf = new WorkspaceFile();
            wf.setId(SnowflakeId.nextId());
            wf.setWorkspaceId(workspaceId);
            wf.setFileName(finalName);
            wf.setFileSize(fileSize);
            wf.setMinioPath(copiedKey);
            wf.setContentType(match.getContentType());
            wf.setUploadedBy(userId);
            wf.setParentPath(path);
            wf.setIsDirectory(false);
            wf.setFileHash(hash);
            fileMapper.insert(wf);
            return toFileDTO(wf);
        } catch (RuntimeException e) {
            workspaceMapper.releaseUsedSize(workspaceId, fileSize);
            try {
                objectStorage.deleteObject(copiedKey);
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", copiedKey, cleanupError.getMessage());
            }
            throw e;
        }
    }

    /**
     * 单次上传。
     *
     * <p>task-41：以 {@link DigestInputStream} 边写对象存储边算 SHA-256，落库的 {@code file_hash}
     * 永远以服务端实际读到的字节为准（客户端声明的 {@code fileHash} 只用于命中同目录同名去重）。
     * 这样不会为了算哈希把整个文件读两遍，也不会把客户端伪造的 hash 写进库。</p>
     */
    public WorkspaceFileDTO uploadFile(Long workspaceId, Long userId, String parentPath, MultipartFile file, String fileHash) throws Exception {
        checkMember(workspaceId, userId);
        validateUploadLimits(workspaceId, userId, file.getSize());
        // task-118：拖拽上传深路径时自动补父目录（逐级、幂等），否则文件落库但 UI 列不出来
        String path = ensureDirectoryPath(workspaceId, userId, parentPath);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        WorkspaceFile existing = findSameHashFile(workspaceId, path, originalName, fileHash);
        if (existing != null) return toFileDTO(existing);

        String fileName = resolveFileName(workspaceId, path, originalName);
        String minioPath = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + fileName;
        reserveStorage(workspaceId, file.getSize());
        MessageDigest digest = newSha256();
        try {
            try (InputStream raw = file.getInputStream();
                 DigestInputStream in = new DigestInputStream(raw, digest)) {
                objectStorage.putObject(minioPath, in, file.getSize(), file.getContentType());
            }
            String computedHash = HexFormat.of().formatHex(digest.digest());
            return createWorkspaceFile(workspaceId, userId, path, fileName, file.getSize(),
                    minioPath, file.getContentType(), computedHash);
        } catch (Exception e) {
            workspaceMapper.releaseUsedSize(workspaceId, file.getSize());
            // 对象可能已在 putObject 阶段写入而 DB 插入失败：异常路径同步删除，避免孤儿对象
            try {
                objectStorage.deleteObject(minioPath);
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", minioPath, cleanupError.getMessage());
            }
            throw e;
        }
    }

    // ── 供 S3 分片上传（UploadSessionService，task-40）复用单文件上传的校验/配额/建行逻辑 ──

    /** 校验成员身份 + 档位 max_file_size + 工作区剩余空间（快速失败，最终以 reserveUploadQuota 原子 UPDATE 为准）。 */
    public void checkUploadAllowed(Long workspaceId, Long userId, long fileSize) {
        checkMember(workspaceId, userId);
        validateUploadLimits(workspaceId, userId, fileSize);
    }

    /** 原子占用空间，失败抛 413；分片 complete 在建行前调用。 */
    public void reserveUploadQuota(Long workspaceId, long fileSize) {
        reserveStorage(workspaceId, fileSize);
    }

    /** 释放空间占用（complete 失败或建行失败时回滚），绝不允许负值（mapper 内 GREATEST）。 */
    public void releaseUploadQuota(Long workspaceId, long fileSize) {
        workspaceMapper.releaseUsedSize(workspaceId, fileSize);
    }

    /**
     * 分片 complete 后创建文件行：复用重名与路径规范化逻辑。
     * 调用方需先完成 {@link #checkUploadAllowed} 与 {@link #reserveUploadQuota}。
     *
     * @param fileHash 客户端在 init 时声明的整文件 SHA-256（task-41，可为 null：历史分片流程/未声明）
     */
    public WorkspaceFileDTO createUploadedFile(Long workspaceId, Long userId, String parentPath,
                                               String fileName, long fileSize, String storageKey,
                                               String contentType, String fileHash) {
        // task-118：分片 complete 与单次上传同口径，自动补父目录
        String path = ensureDirectoryPath(workspaceId, userId, parentPath);
        String finalName = resolveFileName(workspaceId, path, fileName);
        return createWorkspaceFile(workspaceId, userId, path, finalName, fileSize, storageKey,
                contentType, fileHash);
    }

    // ── WebDAV（task-44）复用的公共入口：权限、路径解析、目录/流式上传、移动/复制 ──

    /** 工作区成员校验（WebDAV 等外部协议入口复用）。 */
    public void ensureMember(Long workspaceId, Long userId) {
        checkMember(workspaceId, userId);
    }

    /** 在指定目录下查找未删除的同名条目（文件或目录）；不存在返回 null。 */
    public WorkspaceFile findActiveEntry(Long workspaceId, String parentPath, String name) {
        List<WorkspaceFile> rows = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .eq(WorkspaceFile::getParentPath, normalizePath(parentPath))
                .eq(WorkspaceFile::getFileName, name)
                .isNull(WorkspaceFile::getDeletedAt));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新建目录（WebDAV MKCOL）；同名已存在抛 405（由调用方映射）。 */
    public WorkspaceFileDTO createDirectory(Long workspaceId, Long userId, String parentPath, String name) {
        checkMember(workspaceId, userId);
        String parent = normalizePath(parentPath);
        validateEntryName(name);
        if (findActiveEntry(workspaceId, parent, name) != null) {
            throw new BusinessException(405, "同名文件或目录已存在");
        }
        WorkspaceFile dir = new WorkspaceFile();
        dir.setId(SnowflakeId.nextId());
        dir.setWorkspaceId(workspaceId);
        dir.setFileName(name);
        dir.setFileSize(0L);
        // minio_path 在 DB 是 NOT NULL：目录没有对象，落空串而不是 null（task-47）
        dir.setMinioPath("");
        dir.setContentType(CONTENT_TYPE_DIRECTORY);
        dir.setParentPath(parent);
        dir.setIsDirectory(true);
        dir.setUploadedBy(userId);
        fileMapper.insert(dir);
        return toFileDTO(dir);
    }

    // ── task-118：REST 建目录 + 上传自动补父目录 ──

    /**
     * REST 建目录（个人空间 / 工作区共用）：父目录必须已存在（404，不静默逐级建），
     * 同名未删除条目 → 409，非法名 → 400；目录不占配额（不改 used_size）。
     */
    public WorkspaceFileDTO createFolder(Long workspaceId, Long userId, String parentPath, String name) {
        checkMember(workspaceId, userId);
        String parent = normalizePath(parentPath);
        validateEntryName(name);
        requireActiveParentDirectory(workspaceId, parent);
        if (findActiveEntry(workspaceId, parent, name) != null) {
            throw new BusinessException(409, "同名文件或目录已存在");
        }
        WorkspaceFile dir = buildDirectoryRow(workspaceId, userId, parent, name);
        try {
            fileMapper.insert(dir);
        } catch (DuplicateKeyException e) {
            // 并发同名：唯一索引兜底（V24 起部分唯一索引只约束未删除行）
            throw new BusinessException(409, "同名文件或目录已存在");
        }
        return toFileDTO(dir);
    }

    /**
     * 上传前逐级补齐缺失目录（task-118）：parentPath 缺哪级补哪级，幂等；
     * 并发创建同一目录时把唯一键冲突视为「已存在」；非法段名 → 400。
     *
     * @return 规范化后的父路径（供后续建文件行使用）
     */
    public String ensureDirectoryPath(Long workspaceId, Long userId, String parentPath) {
        checkMember(workspaceId, userId);
        String path = normalizePath(parentPath);
        if ("/".equals(path)) {
            return path;
        }
        String[] segments = path.substring(1).split("/");
        // 先整体校验（任一段非法 → 400，且不落任何目录行），再逐级创建
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                validateEntryName(segment);
            }
        }
        String current = "/";
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (findActiveEntry(workspaceId, current, segment) == null) {
                WorkspaceFile dir = buildDirectoryRow(workspaceId, userId, current, segment);
                try {
                    fileMapper.insert(dir);
                } catch (DuplicateKeyException e) {
                    // 并发上传同一深路径：别人已经建好，视为已存在
                    log.debug("directory concurrently created: ws={}, path={}/{}", workspaceId, current, segment);
                }
            }
            current = "/".equals(current) ? "/" + segment : current + "/" + segment;
        }
        return path;
    }

    private WorkspaceFile buildDirectoryRow(Long workspaceId, Long userId, String parentPath, String name) {
        WorkspaceFile dir = new WorkspaceFile();
        dir.setId(SnowflakeId.nextId());
        dir.setWorkspaceId(workspaceId);
        dir.setFileName(name);
        dir.setFileSize(0L);
        // minio_path 在 DB 是 NOT NULL：目录没有对象，落空串而不是 null（task-47）
        dir.setMinioPath("");
        dir.setContentType(CONTENT_TYPE_DIRECTORY);
        dir.setParentPath(parentPath);
        dir.setIsDirectory(true);
        dir.setUploadedBy(userId);
        return dir;
    }

    /** 父路径必须指向一个未删除的目录（根目录 "/" 隐式存在）；否则 404。 */
    private void requireActiveParentDirectory(Long workspaceId, String parentPath) {
        if ("/".equals(parentPath)) {
            return;
        }
        int idx = parentPath.lastIndexOf('/');
        String grandParent = idx <= 0 ? "/" : parentPath.substring(0, idx);
        String dirName = parentPath.substring(idx + 1);
        WorkspaceFile parentEntry = findActiveEntry(workspaceId, grandParent, dirName);
        if (parentEntry == null || !Boolean.TRUE.equals(parentEntry.getIsDirectory())) {
            throw new BusinessException(404, "父目录不存在");
        }
    }

    /**
     * 流式单文件上传（WebDAV PUT）：边写对象存储边算 SHA-256，原子占配额，失败回滚。
     * 与 {@link #uploadFile} 语义一致，区别是入参为原始流（WebDAV 没有 MultipartFile）。
     */
    public WorkspaceFileDTO uploadStream(Long workspaceId, Long userId, String parentPath, String fileName,
                                         InputStream in, long size, String contentType) throws Exception {
        checkMember(workspaceId, userId);
        validateUploadLimits(workspaceId, userId, size);
        validateEntryName(fileName);
        // WebDAV PUT 不自动建父目录（协议要求客户端先 MKCOL），父缺失按 409 处理
        requireActiveParentDirectory(workspaceId, normalizePath(parentPath));
        String path = normalizePath(parentPath);
        String finalName = resolveFileName(workspaceId, path, fileName);
        String objectKey = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + finalName;
        MessageDigest digest = newSha256();
        reserveStorage(workspaceId, size);
        try (DigestInputStream stream = new DigestInputStream(in, digest)) {
            objectStorage.putObject(objectKey, stream, size, contentType);
            String computedHash = HexFormat.of().formatHex(digest.digest());
            return createWorkspaceFile(workspaceId, userId, path, finalName, size, objectKey, contentType, computedHash);
        } catch (Exception e) {
            workspaceMapper.releaseUsedSize(workspaceId, size);
            try {
                objectStorage.deleteObject(objectKey);
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", objectKey, cleanupError.getMessage());
            }
            throw e;
        }
    }

    /**
     * 移动/重命名（WebDAV MOVE）：同一工作区内；目录整棵子树 parent_path 同步改写；
     * 不改变配额（对象键不动，文件身份仍是同一行）。
     */
    public WorkspaceFileDTO moveEntry(Long workspaceId, Long userId, Long fileId,
                                      String newParentPath, String newName) {
        checkMember(workspaceId, userId);
        WorkspaceFile wf = requireActiveFile(workspaceId, fileId);
        String parent = normalizePath(newParentPath);
        validateEntryName(newName);
        if (parent.equals(normalizePath(wf.getParentPath())) && newName.equals(wf.getFileName())) {
            return toFileDTO(wf);
        }
        WorkspaceFile collision = findActiveEntry(workspaceId, parent, newName);
        if (collision != null && !collision.getId().equals(fileId)) {
            throw new BusinessException(409, "目标路径已存在同名条目");
        }
        String oldBase = FileTree.childBase(wf);
        String newBase = FileTree.joinPath(parent, newName);
        if (Boolean.TRUE.equals(wf.getIsDirectory())) {
            List<WorkspaceFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                    .isNull(WorkspaceFile::getDeletedAt)
                    .and(w -> w.eq(WorkspaceFile::getParentPath, oldBase)
                            .or().likeRight(WorkspaceFile::getParentPath, oldBase + "/")));
            for (WorkspaceFile row : candidates) {
                // likeRight 不转义 %/_，按逻辑路径二次过滤
                if (FileTree.isUnder(row, oldBase)) {
                    row.setParentPath(newBase + row.getParentPath().substring(oldBase.length()));
                    fileMapper.updateById(row);
                }
            }
        }
        wf.setParentPath(parent);
        wf.setFileName(newName);
        fileMapper.updateById(wf);
        return toFileDTO(wf);
    }

    /**
     * 移动（task-132）：同空间只改 {@code parent_path}/{@code file_name}（不动对象、不动
     * {@code used_size}）；跨空间则在同一事务里把整棵子树（目录按递归）换 workspace，
     * 目标空间用原子 {@code reserveUsedSize} 占配额（不足 413），源空间用原子
     * {@code releaseUsedSize}（GREATEST 防下溢）。行是同一批，**不复制 RustFS 对象**——
     * 对象键仍指向原对象，仅行归属变化。
     *
     * <p>校验：目标目录必须存在且为目录（404）；目标同名（未删）409；目录移进自身子孙 400；
     * {@code targetParentPath} 必须以 {@code /} 开头且不含 {@code .}/{@code ..} 段（400）；
     * 源/目标都要有权限（工作区成员，个人空间即 owner），否则 403；源已删 404。
     * 与 rename/delete 的鉴权口径一致：只要求成员，不要求上传者本人。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceFileDTO moveFile(Long userId, Long sourceWorkspaceId, Long fileId,
                                     Long targetWorkspaceId, String targetParentPath, String newName) {
        checkMember(sourceWorkspaceId, userId);
        WorkspaceFile source = requireActiveFile(sourceWorkspaceId, fileId);
        Long targetWs = targetWorkspaceId != null ? targetWorkspaceId : sourceWorkspaceId;
        if (!targetWs.equals(sourceWorkspaceId)) {
            checkMember(targetWs, userId);
        }
        // 目标空间必须存在（工作区被删/非法 id → 404）
        getWorkspaceEntity(targetWs);

        String parent = normalizeMoveParent(targetParentPath, source.getParentPath());
        String name = newName != null && !newName.isBlank() ? newName.trim() : source.getFileName();
        validateEntryName(name);
        boolean sameWorkspace = targetWs.equals(sourceWorkspaceId);

        // 同空间：移到原位置且不改名 → 幂等返回，不写库
        if (sameWorkspace && parent.equals(normalizePath(source.getParentPath()))
                && name.equals(source.getFileName())) {
            return toFileDTO(source);
        }
        // 目标目录必须存在（"/" 为根）
        requireActiveDirectory(targetWs, parent);
        // 防环：目录不能移动到自身或自己的子孙目录下（仅同空间可能成环）
        if (sameWorkspace && Boolean.TRUE.equals(source.getIsDirectory())) {
            String selfBase = FileTree.childBase(source);
            if (parent.equals(selfBase) || parent.startsWith(selfBase + "/")) {
                throw new BusinessException(400, "不能把目录移动到自身或其子孙目录");
            }
        }
        // 目标同名冲突（未删除行）
        WorkspaceFile collision = findActiveEntry(targetWs, parent, name);
        if (collision != null && !collision.getId().equals(fileId)) {
            throw new BusinessException(409, "目标已存在同名文件/目录");
        }

        if (sameWorkspace) {
            // 同空间：只改行的路径/名字，对象键与 used_size 都不动
            moveRowsSameWorkspace(source, parent, name);
            return toFileDTO(source);
        }

        // 跨空间：目录按子树总字节算配额；先原子占目标，再原子释放源，最后换归属（同一事务）
        long size = activeSubtree(sourceWorkspaceId, source).stream()
                .mapToLong(f -> f.getFileSize() == null ? 0L : f.getFileSize())
                .sum();
        if (size > 0) {
            reserveStorage(targetWs, size); // 原子 UPDATE：used_size + size <= max_size，失败 413
            workspaceMapper.releaseUsedSize(sourceWorkspaceId, size); // 原子 SET used_size = GREATEST(used_size - size, 0)
        }
        try {
            moveRowsAcrossWorkspaces(sourceWorkspaceId, targetWs, source, parent, name);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发移动撞部分唯一索引 → 409（事务回滚，配额一并回滚）
            throw new BusinessException(409, "目标已存在同名文件/目录");
        }
        return toFileDTO(source);
    }

    /** 同空间移动：目录整棵子树的 parent_path 同步改写（对象键不变、配额不变）。 */
    private void moveRowsSameWorkspace(WorkspaceFile source, String parent, String name) {
        String oldBase = FileTree.childBase(source);
        String newBase = FileTree.joinPath(parent, name);
        List<WorkspaceFile> subtree = activeSubtree(source.getWorkspaceId(), source);
        for (WorkspaceFile row : subtree) {
            if (row.getId().equals(source.getId())) {
                continue;
            }
            row.setParentPath(newBase + row.getParentPath().substring(oldBase.length()));
            fileMapper.updateById(row);
        }
        source.setParentPath(parent);
        source.setFileName(name);
        fileMapper.updateById(source);
    }

    /** 跨空间移动：整棵子树换 workspace_id；目录保持相对结构；对象键不动。 */
    private void moveRowsAcrossWorkspaces(Long sourceWorkspaceId, Long targetWorkspaceId,
                                          WorkspaceFile source, String parent, String name) {
        String oldBase = FileTree.childBase(source);
        String newBase = FileTree.joinPath(parent, name);
        List<WorkspaceFile> subtree = activeSubtree(sourceWorkspaceId, source);
        for (WorkspaceFile row : subtree) {
            if (row.getId().equals(source.getId())) {
                continue;
            }
            row.setWorkspaceId(targetWorkspaceId);
            row.setParentPath(newBase + row.getParentPath().substring(oldBase.length()));
            fileMapper.updateById(row);
        }
        source.setWorkspaceId(targetWorkspaceId);
        source.setParentPath(parent);
        source.setFileName(name);
        fileMapper.updateById(source);
    }

    /** 目标父路径规范化：空 → 源目录；非空必须 "/" 开头、无 "."/".." 段、去尾斜杠。 */
    private String normalizeMoveParent(String targetParentPath, String fallbackParentPath) {
        if (targetParentPath == null || targetParentPath.isBlank()) {
            return normalizePath(fallbackParentPath);
        }
        String path = targetParentPath.trim();
        if (!path.startsWith("/")) {
            throw new BusinessException(400, "目标路径必须以 / 开头");
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException(400, "目标路径包含非法片段");
            }
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 目标目录必须存在且是未删除的目录，否则 404（"/" 视为存在的根）。 */
    private void requireActiveDirectory(Long workspaceId, String dirPath) {
        if ("/".equals(dirPath)) {
            return;
        }
        int idx = dirPath.lastIndexOf('/');
        String dirParent = idx <= 0 ? "/" : dirPath.substring(0, idx);
        String dirName = dirPath.substring(idx + 1);
        WorkspaceFile dir = findActiveEntry(workspaceId, dirParent, dirName);
        if (dir == null || !Boolean.TRUE.equals(dir.getIsDirectory())) {
            throw new BusinessException(404, "目标目录不存在");
        }
    }

    /** 复制（WebDAV COPY）：文件走 S3 CopyObject；目录递归复制；逐文件原子占配额。 */
    public WorkspaceFileDTO copyEntry(Long workspaceId, Long userId, Long fileId,
                                      String destParentPath, String destName) {
        checkMember(workspaceId, userId);
        WorkspaceFile source = requireActiveFile(workspaceId, fileId);
        String parent = normalizePath(destParentPath);
        validateEntryName(destName);
        if (findActiveEntry(workspaceId, parent, destName) != null) {
            throw new BusinessException(409, "目标路径已存在同名条目");
        }
        return copyEntryInternal(workspaceId, userId, source, parent, destName);
    }

    private WorkspaceFileDTO copyEntryInternal(Long workspaceId, Long userId, WorkspaceFile source,
                                               String parent, String name) {
        if (Boolean.TRUE.equals(source.getIsDirectory())) {
            WorkspaceFile dir = new WorkspaceFile();
            dir.setId(SnowflakeId.nextId());
            dir.setWorkspaceId(workspaceId);
            dir.setFileName(name);
            dir.setFileSize(0L);
            // 同 createDirectory：目录行 minio_path 不能为 null（task-47）
            dir.setMinioPath("");
            dir.setContentType(CONTENT_TYPE_DIRECTORY);
            dir.setParentPath(parent);
            dir.setIsDirectory(true);
            dir.setUploadedBy(userId);
            fileMapper.insert(dir);
            String sourceBase = FileTree.childBase(source);
            List<WorkspaceFile> children = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                    .eq(WorkspaceFile::getParentPath, sourceBase)
                    .isNull(WorkspaceFile::getDeletedAt));
            String destBase = FileTree.joinPath(parent, name);
            for (WorkspaceFile child : children) {
                copyEntryInternal(workspaceId, userId, child, destBase, child.getFileName());
            }
            return toFileDTO(dir);
        }
        long size = source.getFileSize() == null ? 0L : source.getFileSize();
        validateUploadLimits(workspaceId, userId, size);
        String finalName = resolveFileName(workspaceId, parent, name);
        String newKey = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + finalName;
        reserveStorage(workspaceId, size);
        try {
            objectStorage.copyObject(source.getMinioPath(), newKey);
            return createWorkspaceFile(workspaceId, userId, parent, finalName, size, newKey,
                    source.getContentType(), source.getFileHash());
        } catch (RuntimeException e) {
            workspaceMapper.releaseUsedSize(workspaceId, size);
            try {
                objectStorage.deleteObject(newKey);
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", newKey, cleanupError.getMessage());
            }
            throw e;
        }
    }

    /**
     * 文件转存（task-102）：把来源文件复制到目标工作区/目录，服务端 S3 CopyObject，不读回字节。
     *
     * <p><b>计量口径</b>：转存没有外网字节流动，<b>不计入 {@code relay_bytes_up/down}</b>
     * （这是转存与下载的本质区别）；但目标空间照常原子占配额（失败 413）。</p>
     *
     * <p><b>权限</b>：来源需可读（工作区成员 / 个人空间 owner），目标需为成员；
     * 源不存在 / 已软删统一 404（不泄露存在性），目标不可写 403。</p>
     *
     * <p><b>命名</b>：`newName` 缺省用源文件名；目标目录已有同名条目 → 400，绝不静默覆盖
     * （与实例化「同名自动 (2)」策略不同：转存必须显式改名，避免用户误覆盖）。</p>
     *
     * @param targetWorkspaceId 目标工作区（可为 null：与来源同工作区）；跨工作区同样支持
     */
    public WorkspaceFileDTO copyFile(Long userId, Long sourceWorkspaceId, Long fileId,
                                     Long targetWorkspaceId, String targetParentPath, String newName) {
        checkMember(sourceWorkspaceId, userId);
        WorkspaceFile source = requireActiveFile(sourceWorkspaceId, fileId);
        if (Boolean.TRUE.equals(source.getIsDirectory())) {
            throw new BusinessException(400, "本期仅支持单个文件转存（目录暂不支持）");
        }
        Long targetWs = targetWorkspaceId != null ? targetWorkspaceId : sourceWorkspaceId;
        checkMember(targetWs, userId);
        getWorkspaceEntity(targetWs);

        String parent = normalizePath(targetParentPath != null ? targetParentPath : source.getParentPath());
        String name = newName != null && !newName.isBlank() ? newName.trim() : source.getFileName();
        validateEntryName(name);
        if (findActiveEntry(targetWs, parent, name) != null) {
            throw new BusinessException(400, "目标目录已存在同名文件，请改名");
        }

        long size = source.getFileSize() == null ? 0L : source.getFileSize();
        validateUploadLimits(targetWs, userId, size);
        String newKey = "workspaces/" + targetWs + "/" + UUID.randomUUID() + "/" + name;
        reserveStorage(targetWs, size);
        try {
            objectStorage.copyObject(source.getMinioPath(), newKey);
            // uploaded_by = 当前用户；file_hash 原样复制（秒传可命中）；created_at 由 DB 默认值；trash_* 为 null
            return createWorkspaceFile(targetWs, userId, parent, name, size, newKey,
                    source.getContentType(), source.getFileHash());
        } catch (RuntimeException e) {
            workspaceMapper.releaseUsedSize(targetWs, size);
            try {
                objectStorage.deleteObject(newKey);
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", newKey, cleanupError.getMessage());
            }
            throw e;
        }
    }

    /** WebDAV 条目名校验：非空、无路径分隔符、无 "."/".."，长度 <= 255。 */
    private static void validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "名称不能为空");
        }
        if (name.length() > 255) {
            throw new BusinessException(400, "名称过长");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || ".".equals(name) || "..".equals(name)) {
            throw new BusinessException(400, "名称包含非法字符");
        }
        // task-118：REST 建目录/上传自动补目录复用同一套名称规则，补齐各端都不可用的字符与控制字符
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new BusinessException(400, "名称包含控制字符");
            }
            if (c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                throw new BusinessException(400, "名称包含非法字符");
            }
        }
    }

    /**
     * 秒传（instant upload，task-41）。
     *
     * <p><b>安全口径</b>：只在调用者<em>本来就有权限</em>的范围（目标工作区 + 自己作为成员的工作区 +
     * 自己拥有的工作区）内按 {@code (file_hash, file_size)} 找源文件，再做 S3 server-side CopyObject。
     * 不校验客户端声明的 hash 与源对象真实内容是否一致——最坏情况是用户把自己本来就能下载的文件
     * 再复制一份，不产生越权或泄露；<b>绝不做全站查找</b>。</p>
     *
     * <p><b>计量口径</b>：秒传没有外网下行字节流动，不计流量；但新文件照常原子占配额、占空间。</p>
     *
     * @return 新建的文件行（不含 {@code instant} 标记，由 Controller 组装）
     * @throws BusinessException 404 未命中秒传（客户端应回退普通上传）；400 参数非法；413 配额不足
     */
    public WorkspaceFileDTO instantUpload(Long workspaceId, Long userId, String parentPath,
                                          String fileName, String fileHash, Long fileSize,
                                          String contentType) {
        if (fileHash == null || !fileHash.trim().matches("[0-9a-f]{64}")) {
            throw new BusinessException(400, "fileHash 必须是 64 位小写 hex");
        }
        if (fileSize == null || fileSize <= 0) {
            throw new BusinessException(400, "文件大小必须大于 0");
        }
        checkMember(workspaceId, userId);
        validateUploadLimits(workspaceId, userId, fileSize);

        // task-118：秒传同样补父目录，保证 UI 可列
        String path = ensureDirectoryPath(workspaceId, userId, parentPath);
        WorkspaceFile source = findInstantSource(workspaceId, userId, fileHash, fileSize);
        if (source == null) {
            throw new BusinessException(404, "未命中秒传");
        }
        String originalName = fileName != null && !fileName.isBlank() ? fileName : source.getFileName();
        String finalName = resolveFileName(workspaceId, path, originalName);
        String newKey = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + finalName;
        String type = contentType != null && !contentType.isBlank() ? contentType : source.getContentType();

        reserveStorage(workspaceId, fileSize);
        try {
            objectStorage.copyObject(source.getMinioPath(), newKey);
            return createWorkspaceFile(workspaceId, userId, path, finalName, fileSize, newKey, type, fileHash);
        } catch (RuntimeException e) {
            workspaceMapper.releaseUsedSize(workspaceId, fileSize);
            try {
                objectStorage.deleteObject(newKey);
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", newKey, cleanupError.getMessage());
            }
            throw e;
        }
    }

    /**
     * 秒传源文件查找：限定在调用者本来就有权限的工作区集合内（见 {@link #instantUpload} 安全口径）。
     * 命中多个时优先同工作区副本。
     */
    private WorkspaceFile findInstantSource(Long workspaceId, Long userId, String fileHash, long fileSize) {
        Set<Long> accessible = instantScopeWorkspaceIds(workspaceId, userId);

        List<WorkspaceFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .in(WorkspaceFile::getWorkspaceId, accessible)
                .eq(WorkspaceFile::getFileHash, fileHash)
                .eq(WorkspaceFile::getFileSize, fileSize)
                .eq(WorkspaceFile::getIsDirectory, false)
                .isNull(WorkspaceFile::getDeletedAt));
        return candidates.stream()
                .filter(f -> f.getMinioPath() != null && !f.getMinioPath().isBlank())
                .min(Comparator.comparing((WorkspaceFile f) -> !workspaceId.equals(f.getWorkspaceId())))
                .orElse(null);
    }

    /**
     * 秒传的查找范围（安全边界）：目标工作区 + 调用者在成员表里的工作区 + 调用者自己拥有的工作区。
     * 包级可见以便单测直接断言范围；<b>绝不允许</b>放开成全站查找。
     */
    Set<Long> instantScopeWorkspaceIds(Long workspaceId, Long userId) {
        Set<Long> accessible = new HashSet<>();
        accessible.add(workspaceId);
        memberMapper.selectList(new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getUserId, userId))
                .forEach(member -> accessible.add(member.getWorkspaceId()));
        workspaceMapper.selectList(new LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getOwnerId, userId))
                .forEach(ws -> accessible.add(ws.getId()));
        return accessible;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String initChunkUpload(Long workspaceId, Long userId, String fileName, long fileSize,
                                  String parentPath, String fileHash, String contentType, Integer chunkSize,
                                  String requestedUploadId) throws Exception {
        checkMember(workspaceId, userId);
        if (fileSize <= 0) {
            throw new BusinessException(400, "文件大小必须大于 0");
        }
        int effectiveChunkSize = chunkSize != null && chunkSize > 0
                ? chunkSize
                : configService.getInt("transfer.chunk_size", 5242880);
        long maxChunkSize = configService.getLong("transfer.max_chunk_size", 64L * 1024 * 1024);
        if (effectiveChunkSize > maxChunkSize) {
            throw new BusinessException(400, "分片大小超过上限(" + formatLimit(maxChunkSize) + ")");
        }
        long totalChunks = (fileSize + effectiveChunkSize - 1L) / effectiveChunkSize;
        long maxChunks = configService.getLong("transfer.max_chunks", 200_000L);
        if (totalChunks <= 0 || totalChunks > maxChunks) {
            throw new BusinessException(400, "分片数量超过上限(" + maxChunks + ")");
        }
        // 声明大小必须 ≤ 用户档位 max_file_size，且工作区剩余空间足够（正式占用以原子 UPDATE 为准）
        validateUploadLimits(workspaceId, userId, fileSize);

        String path = normalizePath(parentPath);
        String originalName = fileName != null && !fileName.isBlank() ? fileName : "file";
        WorkspaceFile existing = findSameHashFile(workspaceId, path, originalName, fileHash);
        if (existing != null) return "instant:" + existing.getId();

        if (requestedUploadId != null && !requestedUploadId.isBlank()) {
            WorkspaceUploadSession existingSession = loadUploadSession(requestedUploadId);
            if (existingSession != null
                    && existingSession.workspaceId().equals(workspaceId)
                    && existingSession.userId().equals(userId)
                    && existingSession.fileSize() == fileSize
                    && existingSession.chunkSize() == effectiveChunkSize
                    && existingSession.parentPath().equals(path)
                    && existingSession.originalName().equals(originalName)
                    && safeEquals(existingSession.fileHash(), fileHash)) {
                uploadSessions.put(requestedUploadId, existingSession);
                uploadLocks.putIfAbsent(requestedUploadId, new Object());
                return requestedUploadId;
            }
        }

        String finalName = resolveFileName(workspaceId, path, originalName);
        String uploadId = UUID.randomUUID().toString();
        Path tempPath = Path.of(TEMP_DIR, uploadId + ".tmp");
        Files.createDirectories(tempPath.getParent());
        Files.deleteIfExists(tempPath);
        Files.createFile(tempPath);

        WorkspaceUploadSession session = new WorkspaceUploadSession(
                workspaceId, userId, path, originalName, finalName, fileSize, fileHash, contentType,
                effectiveChunkSize, tempPath.toString(), new HashSet<>());
        uploadSessions.put(uploadId, session);
        uploadLocks.put(uploadId, new Object());
        saveUploadSession(uploadId, session);
        return uploadId;
    }

    public void uploadChunk(Long workspaceId, Long userId, String uploadId, int chunkIndex, InputStream data, long size) throws Exception {
        WorkspaceUploadSession session = getUploadSession(workspaceId, userId, uploadId);
        if (chunkIndex < 0) {
            throw new BusinessException(400, "chunk index out of range");
        }
        long offset = (long) chunkIndex * session.chunkSize();
        if (offset >= session.fileSize()) {
            throw new BusinessException(400, "chunk index out of range");
        }
        // 单分片上限：该 index 期望的字节数（末片可小于 chunkSize）。
        // 修复“声明 1B 实写 5GB”：忽略客户端声明，按实际读取字节数严格校验。
        long expected = Math.min(session.chunkSize(), session.fileSize() - offset);

        Object lock = uploadLocks.computeIfAbsent(uploadId, ignored -> new Object());
        synchronized (lock) {
            if (session.uploadedChunks().contains(chunkIndex)) {
                return; // 幂等重传：该分片已完整接收，不再重复写
            }
            // 客户端 HTTP 声明大小必须与本分片期望大小一致（快速失败，失败不落盘）
            if (size > 0 && size != expected) {
                throw new BusinessException(400, "chunk size mismatch: declared " + size
                        + " bytes, expected " + expected);
            }
            long receivedBytes = uploadedBytes(session);
            if (receivedBytes + expected > session.fileSize()) {
                throw new BusinessException(400, "累计上传字节数超过声明的文件大小: " + session.fileSize());
            }
            long written = 0L;
            try (RandomAccessFile raf = new RandomAccessFile(session.tempPath(), "rw")) {
                raf.seek(offset);
                byte[] buf = new byte[1024 * 1024];
                int read;
                while ((read = data.read(buf)) != -1) {
                    written += read;
                    if (written > expected) {
                        throw new BusinessException(400, "chunk too large: expected <= " + expected
                                + " bytes (chunkSize=" + session.chunkSize() + "), got more");
                    }
                    raf.write(buf, 0, read);
                }
            }
            if (written != expected) {
                throw new BusinessException(400, "chunk size mismatch: expected " + expected + " bytes, got " + written);
            }
            session.uploadedChunks().add(chunkIndex);
            saveUploadSession(uploadId, session);
        }
    }

    /** 已接收分片的字节总和；分片索引合法时天然 ≤ fileSize，这里显式兜底校验。 */
    private static long uploadedBytes(WorkspaceUploadSession session) {
        long sum = 0L;
        for (Integer index : session.uploadedChunks()) {
            long offset = (long) index * session.chunkSize();
            sum += Math.min(session.chunkSize(), session.fileSize() - offset);
        }
        return sum;
    }

    /** 校验失败后的清理：删除临时文件/元数据并移除会话，允许客户端从头重传。 */
    private void cleanupUpload(String uploadId, WorkspaceUploadSession session) {
        try {
            Files.deleteIfExists(Path.of(session.tempPath()));
            Files.deleteIfExists(metadataPath(uploadId));
        } catch (IOException e) {
            log.warn("failed to clean incomplete upload {}: {}", uploadId, e.getMessage());
        }
        uploadSessions.remove(uploadId);
        uploadLocks.remove(uploadId);
    }

    public Map<String, Object> chunkStatus(Long workspaceId, Long userId, String uploadId) {
        WorkspaceUploadSession session = getUploadSession(workspaceId, userId, uploadId);
        return Map.of(
                "uploadId", uploadId,
                "chunkSize", session.chunkSize(),
                "uploadedChunks", new TreeSet<>(session.uploadedChunks())
        );
    }

    @Transactional
    public WorkspaceFileDTO completeChunkUpload(Long workspaceId, Long userId, String uploadId) throws Exception {
        if (uploadId != null && uploadId.startsWith("instant:")) {
            Long fileId = Long.parseLong(uploadId.substring("instant:".length()));
            WorkspaceFile existing = fileMapper.selectById(fileId);
            if (existing == null || existing.getDeletedAt() != null || !existing.getWorkspaceId().equals(workspaceId)) {
                throw new BusinessException(404, "file not found");
            }
            checkMember(workspaceId, userId);
            return toFileDTO(existing);
        }

        WorkspaceUploadSession session = getUploadSession(workspaceId, userId, uploadId);
        Path tempPath = Path.of(session.tempPath());
        long actualSize = Files.size(tempPath);
        int chunkCount = (int) Math.ceil((double) session.fileSize() / session.chunkSize());
        if (session.uploadedChunks().size() < chunkCount || actualSize != session.fileSize()) {
            // 收到的总字节数/分片数 != 声明大小：拒绝入库，并清理临时文件与元数据
            cleanupUpload(uploadId, session);
            throw new BusinessException(400, "upload chunks incomplete");
        }

        validateUploadLimits(workspaceId, userId, session.fileSize());
        long fileSize = session.fileSize();
        // 原子占用逻辑空间（并发安全）；失败即空间不足
        reserveStorage(workspaceId, fileSize);
        boolean completed = false;
        String minioPath = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + session.fileName();
        try {
            try (InputStream is = Files.newInputStream(tempPath)) {
                objectStorage.putObject(minioPath, is, fileSize, session.contentType());
            }
            WorkspaceFileDTO dto = createWorkspaceFile(workspaceId, userId, session.parentPath(),
                    session.fileName(), fileSize, minioPath, session.contentType(), session.fileHash());
            completed = true;
            return dto;
        } finally {
            if (!completed) {
                workspaceMapper.releaseUsedSize(workspaceId, fileSize);
                // putObject 可能已成功而后续入库失败：删除对象，避免孤儿占用存储
                try {
                    objectStorage.deleteObject(minioPath);
                } catch (RuntimeException cleanupError) {
                    log.warn("failed to delete orphan object {}: {}", minioPath, cleanupError.getMessage());
                }
            }
            Files.deleteIfExists(tempPath);
            Files.deleteIfExists(metadataPath(uploadId));
            uploadSessions.remove(uploadId);
            uploadLocks.remove(uploadId);
        }
    }

    private WorkspaceFileDTO createWorkspaceFile(Long workspaceId, Long userId, String path,
                                                 String fileName, long fileSize, String minioPath,
                                                 String contentType, String fileHash) {
        WorkspaceFile wf = new WorkspaceFile();
        wf.setId(SnowflakeId.nextId());
        wf.setWorkspaceId(workspaceId);
        wf.setFileName(fileName);
        wf.setFileSize(fileSize);
        wf.setMinioPath(minioPath);
        wf.setContentType(contentType);
        wf.setUploadedBy(userId);
        wf.setParentPath(path);
        wf.setIsDirectory(false);
        wf.setFileHash(fileHash);
        fileMapper.insert(wf);
        // used_size 已由 reserveUsedSize 原子占用，这里不再读-改-写
        return toFileDTO(wf);
    }

    /**
     * 原子占用逻辑空间：单条 `UPDATE ... WHERE used_size + ? <= max_size`，
     * 影响行数 0 即空间不足（并发请求中只有凑得上的那些能成功）。失败返回 code=413。
     */
    private void reserveStorage(Long workspaceId, long fileSize) {
        if (workspaceMapper.reserveUsedSize(workspaceId, fileSize) == 0) {
            throw new BusinessException(413, "空间不足，当前配额无法容纳该文件");
        }
    }

    /**
     * 公开的原子占用（task-162：回收站恢复时按子树字节重新预占）；空间不足抛 413。
     */
    public void reserveSpace(Long workspaceId, long bytes) {
        if (workspaceId != null && bytes > 0) {
            reserveStorage(workspaceId, bytes);
        }
    }

    /**
     * 公开的原子释放（task-162）：单条 {@code UPDATE ... GREATEST(used_size - ?, 0)}，
     * 不读改写、不会出现负数；{@code bytes <= 0} 或 {@code workspaceId == null} 时忽略。
     */
    public void releaseSpace(Long workspaceId, long bytes) {
        if (workspaceId != null && bytes > 0) {
            workspaceMapper.releaseUsedSize(workspaceId, bytes);
        }
    }

    private Workspace validateUploadLimits(Long workspaceId, Long userId, long fileSize) {
        Entitlements entitlements = entitlementResolver.resolve(userId);
        long maxFileSize = entitlements.limits().maxFileSize();
        if (maxFileSize > 0 && fileSize > maxFileSize) {
            throw new BusinessException(400, "file exceeds plan max limit(" + formatLimit(maxFileSize) + ")");
        }

        // 快速失败：并发下最终以 reserveStorage 的原子 UPDATE 为准
        Workspace ws = getWorkspaceEntity(workspaceId);
        if (ws.getUsedSize() + fileSize > ws.getMaxSize()) {
            throw new BusinessException(413, "空间不足，当前配额无法容纳该文件");
        }
        return ws;
    }

    private static String formatLimit(long bytes) {
        if (bytes >= 1073741824L) return (bytes / 1073741824L) + "GB";
        if (bytes >= 1048576L) return (bytes / 1048576L) + "MB";
        return (bytes / 1024L) + "KB";
    }

    private String normalizePath(String parentPath) {
        return parentPath != null && !parentPath.isBlank() ? parentPath : "/";
    }

    private WorkspaceFile findSameHashFile(Long workspaceId, String path, String fileName, String fileHash) {
        if (fileHash == null || fileHash.isBlank()) return null;
        return fileMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceFile>()
                                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                                .eq(WorkspaceFile::getParentPath, path)
                                .eq(WorkspaceFile::getFileName, fileName)
                                .eq(WorkspaceFile::getIsDirectory, false)
                                .isNull(WorkspaceFile::getDeletedAt))
                .stream()
                .filter(f -> fileHash.equals(f.getFileHash()))
                .findFirst()
                .orElse(null);
    }

    private String resolveFileName(Long workspaceId, String path, String originalName) {
        List<WorkspaceFile> sameName = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>()
                        .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                        .eq(WorkspaceFile::getParentPath, path)
                        .eq(WorkspaceFile::getFileName, originalName)
                        .eq(WorkspaceFile::getIsDirectory, false)
                        .isNull(WorkspaceFile::getDeletedAt));
        if (sameName.isEmpty()) return originalName;

        int dotIdx = originalName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
        String ext = dotIdx > 0 ? originalName.substring(dotIdx) : "";
        int maxSuffix = 0;
        List<WorkspaceFile> allInPath = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>()
                        .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                        .eq(WorkspaceFile::getParentPath, path)
                        .likeRight(WorkspaceFile::getFileName, baseName)
                        .eq(WorkspaceFile::getIsDirectory, false)
                        .isNull(WorkspaceFile::getDeletedAt));
        for (WorkspaceFile ef : allInPath) {
            String name = ef.getFileName();
            if (name.startsWith(baseName + "(") && name.endsWith(")" + ext)) {
                String numStr = name.substring(baseName.length() + 1, name.length() - ext.length() - 1);
                try {
                    maxSuffix = Math.max(maxSuffix, Integer.parseInt(numStr));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return baseName + "(" + (maxSuffix + 1) + ")" + ext;
    }

    private WorkspaceUploadSession getUploadSession(Long workspaceId, Long userId, String uploadId) {
        WorkspaceUploadSession session = uploadSessions.computeIfAbsent(uploadId, this::loadUploadSession);
        if (session == null) throw new BusinessException(404, "upload session not found or expired");
        uploadLocks.putIfAbsent(uploadId, new Object());
        if (!session.workspaceId().equals(workspaceId) || !session.userId().equals(userId)) {
            throw new BusinessException(403, "no permission for upload session");
        }
        return session;
    }

    private Path metadataPath(String uploadId) {
        return Path.of(TEMP_DIR, uploadId + ".properties");
    }

    private void saveUploadSession(String uploadId, WorkspaceUploadSession session) throws Exception {
        Files.createDirectories(Path.of(TEMP_DIR));
        Properties p = new Properties();
        p.setProperty("workspaceId", String.valueOf(session.workspaceId()));
        p.setProperty("userId", String.valueOf(session.userId()));
        p.setProperty("parentPath", session.parentPath());
        p.setProperty("originalName", session.originalName());
        p.setProperty("fileName", session.fileName());
        p.setProperty("fileSize", String.valueOf(session.fileSize()));
        p.setProperty("fileHash", session.fileHash() != null ? session.fileHash() : "");
        p.setProperty("contentType", session.contentType() != null ? session.contentType() : "");
        p.setProperty("chunkSize", String.valueOf(session.chunkSize()));
        p.setProperty("tempPath", session.tempPath());
        p.setProperty("uploadedChunks", session.uploadedChunks().stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        try (var out = Files.newOutputStream(metadataPath(uploadId))) {
            p.store(out, "Bond workspace upload session");
        }
    }

    private WorkspaceUploadSession loadUploadSession(String uploadId) {
        try {
            Path meta = metadataPath(uploadId);
            if (!Files.exists(meta)) return null;
            Properties p = new Properties();
            try (var in = Files.newInputStream(meta)) {
                p.load(in);
            }
            Set<Integer> uploaded = new HashSet<>();
            String chunks = p.getProperty("uploadedChunks", "");
            if (!chunks.isBlank()) {
                Arrays.stream(chunks.split(","))
                        .filter(s -> !s.isBlank())
                        .map(Integer::parseInt)
                        .forEach(uploaded::add);
            }
            return new WorkspaceUploadSession(
                    Long.parseLong(p.getProperty("workspaceId")),
                    Long.parseLong(p.getProperty("userId")),
                    p.getProperty("parentPath", "/"),
                    p.getProperty("originalName", p.getProperty("fileName", "file")),
                    p.getProperty("fileName", "file"),
                    Long.parseLong(p.getProperty("fileSize")),
                    blankToNull(p.getProperty("fileHash")),
                    blankToNull(p.getProperty("contentType")),
                    Integer.parseInt(p.getProperty("chunkSize")),
                    p.getProperty("tempPath"),
                    uploaded);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || a.isBlank()) return b == null || b.isBlank();
        return a.equals(b);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record WorkspaceUploadSession(Long workspaceId, Long userId, String parentPath, String originalName,
                                          String fileName, long fileSize, String fileHash, String contentType,
                                          int chunkSize, String tempPath, Set<Integer> uploadedChunks) {
    }

    public DownloadResult downloadFile(Long workspaceId, Long userId, Long fileId) throws Exception {
        checkMember(workspaceId, userId);
        WorkspaceFile wf = requireActiveFile(workspaceId, fileId);
        // 只计下行：下载者本人；超额降速不断线。计量按实际送出字节在流 close 时落账
        InputStream stream = usageService.meterAndThrottleDownload(
                userId, objectStorage.getObject(wf.getMinioPath()));
        return new DownloadResult(stream, wf.getFileSize());
    }

    public record DownloadResult(InputStream inputStream, long fileSize) {
    }

    /** 预览/缩略图：工作区成员校验，越权/不存在/已进回收站统一 404（不泄露存在性）。 */
    public WorkspaceFile getFileForPreview(Long workspaceId, Long userId, Long fileId) {
        checkMember(workspaceId, userId);
        return requireActiveFile(workspaceId, fileId);
    }

    /** 预览/缩略图：文件必须属于该用户的个人空间（type=personal 且 owner=userId），否则 404。 */
    public WorkspaceFile getPersonalFileForPreview(Long userId, Long fileId) {
        WorkspaceFile wf = fileMapper.selectById(fileId);
        if (wf == null || wf.getDeletedAt() != null) {
            throw new BusinessException(404, "file not found");
        }
        Workspace ws = workspaceMapper.selectById(wf.getWorkspaceId());
        if (ws == null
                || !WorkspaceType.PERSONAL.getValue().equals(ws.getType())
                || !userId.equals(ws.getOwnerId())) {
            throw new BusinessException(404, "file not found");
        }
        return wf;
    }

    /** 打开内容流（Range 由调用方解析）；大文件不整体读入内存。 */
    public InputStream openFileContentStream(WorkspaceFile wf, long offset, long length) throws Exception {
        if (offset <= 0 && length >= wf.getFileSize()) {
            return objectStorage.getObject(wf.getMinioPath());
        }
        return objectStorage.getObject(wf.getMinioPath(), offset, length);
    }

    /** 读取未进回收站的文件；不存在/已软删/跨空间统一 404（不泄露存在性）。 */
    public WorkspaceFile requireActiveFile(Long workspaceId, Long fileId) {
        WorkspaceFile wf = fileMapper.selectById(fileId);
        if (wf == null || wf.getDeletedAt() != null || !wf.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(404, "file not found");
        }
        return wf;
    }

    /**
     * 删除 → 进回收站（软删，V6）。物理对象保留到「彻底删除 / 回收站过期清理」；
     * 目录递归整棵子树。
     *
     * <p>配额语义（task-162 调整）：删除时**立即原子释放** `used_size`
     * （{@code GREATEST(used_size - bytes, 0)}，目录按子树求和一次性扣减）；物理清理
     * （{@code TrashService.purgeRoot}）**不再重复扣减**；恢复时按子树字节重新原子预占，
     * 空间不足返回 413。此口径与 task-88 的「回收站占用」相反，原因：否则用户删除后
     * 空间永远不回收（task-162 缺陷）。</p>
     */
    @Transactional
    public void deleteFile(Long workspaceId, Long userId, Long fileId) {
        checkMember(workspaceId, userId);
        WorkspaceFile wf = requireActiveFile(workspaceId, fileId);
        List<WorkspaceFile> subtree = activeSubtree(workspaceId, wf);
        long totalSize = subtree.stream()
                .mapToLong(f -> f.getFileSize() == null ? 0L : f.getFileSize())
                .sum();

        OffsetDateTime now = OffsetDateTime.now();
        // 保留期按「工作区 owner 生效档位」算（不是操作者），避免成员用自己档位影响 owner 空间
        int retentionDays = retentionDays(ownerIdOf(workspaceId));
        OffsetDateTime expiresAt = retentionDays > 0 ? now.plusDays(retentionDays) : null;
        for (WorkspaceFile row : subtree) {
            fileMapper.update(null, new LambdaUpdateWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getId, row.getId())
                    .set(WorkspaceFile::getDeletedAt, now)
                    .set(WorkspaceFile::getDeletedBy, userId)
                    .set(WorkspaceFile::getTrashExpiresAt, expiresAt));
        }
        if (totalSize > 0) {
            releaseSpace(workspaceId, totalSize);
        }
        log.info("file moved to trash (quota released, task-162): fileId={}, workspace={}, entries={}, bytes={}, retentionDays={}",
                fileId, workspaceId, subtree.size(), totalSize, retentionDays);
    }

    /** 工作区 owner（回收站保留期按 owner 档位算）；行不存在时回退 null。 */
    private Long ownerIdOf(Long workspaceId) {
        Workspace ws = getWorkspaceEntity(workspaceId);
        return ws.getOwnerId();
    }

    /** 软删子树：自身 + 目录下的全部后代（仅未删除行；按 parent_path 前缀）。 */
    private List<WorkspaceFile> activeSubtree(Long workspaceId, WorkspaceFile root) {
        List<WorkspaceFile> rows = new ArrayList<>();
        rows.add(root);
        if (Boolean.TRUE.equals(root.getIsDirectory())) {
            String base = FileTree.childBase(root);
            List<WorkspaceFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                    .isNull(WorkspaceFile::getDeletedAt)
                    .and(w -> w.eq(WorkspaceFile::getParentPath, base)
                            .or().likeRight(WorkspaceFile::getParentPath, base + "/")));
            for (WorkspaceFile candidate : candidates) {
                // likeRight 不会转义 % 和 _，这里按逻辑路径二次过滤，避免把 a_b 当成 a%b
                if (FileTree.isUnder(candidate, base)) {
                    rows.add(candidate);
                }
            }
        }
        return rows;
    }

    /** 回收站保留天数：取 owner 档位 limits（0=不限期）；owner 未知/解析失败回退配置默认值。 */
    /** 回收站保留天数（task-110 统一策略：档位 &gt; 全局配置 trash.retention_days &gt; 7；0=不限期）。 */
    private int retentionDays(Long ownerId) {
        return TrashRetentionPolicy.daysFor(entitlementResolver, configService, ownerId, log);
    }

    public WorkspaceFileDTO renameFile(Long workspaceId, Long userId, Long fileId, String newName) {
        checkMember(workspaceId, userId);
        WorkspaceFile wf = requireActiveFile(workspaceId, fileId);
        wf.setFileName(newName);
        fileMapper.updateById(wf);
        return toFileDTO(wf);
    }

    private void checkMember(Long workspaceId, Long userId) {
        WorkspaceMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(403, "no permission to access workspace");
        }
    }

    private void checkRole(Long workspaceId, Long userId, WorkspaceRole... roles) {
        requireRole(workspaceId, userId, roles);
    }

    private WorkspaceMember requireRole(Long workspaceId, Long userId, WorkspaceRole... roles) {
        WorkspaceMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(403, "no permission to access workspace");
        }
        for (WorkspaceRole role : roles) {
            if (member.getRole().equals(role.getValue())) return member;
        }
        throw new BusinessException(403, "permission denied");
    }

    private Workspace getWorkspaceEntity(Long id) {
        Workspace ws = workspaceMapper.selectById(id);
        if (ws == null) throw new BusinessException(404, "workspace not found");
        return ws;
    }

    private static final long[] ZERO_COUNTS = {0L, 0L};

    /** 单条 SQL 取多个工作区的成员数/文件数；查询失败按 0 处理，不影响列表可用性。 */
    private Map<Long, long[]> workspaceStats(List<Long> workspaceIds) {
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = workspaceMapper.selectStatsByIds(workspaceIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, long[]> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long id = asLong(row.get("workspace_id"));
            if (id > 0) {
                result.put(id, new long[]{asLong(row.get("member_count")), asLong(row.get("file_count"))});
            }
        }
        return result;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private WorkspaceDTO toDTOWithStats(Workspace ws) {
        long[] counts = workspaceStats(List.of(ws.getId())).getOrDefault(ws.getId(), ZERO_COUNTS);
        return toDTO(ws, counts[0], counts[1]);
    }

    private WorkspaceDTO toDTO(Workspace ws, Long memberCount, Long fileCount) {
        WorkspaceDTO dto = new WorkspaceDTO();
        dto.setId(ws.getId());
        dto.setName(ws.getName());
        dto.setDescription(ws.getDescription());
        dto.setOwnerId(ws.getOwnerId());
        dto.setMaxSize(ws.getMaxSize());
        dto.setUsedSize(ws.getUsedSize());
        dto.setMemberCount(memberCount);
        dto.setFileCount(fileCount);
        dto.setCreatedAt(ws.getCreatedAt() != null
                ? ws.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null);
        return dto;
    }

    private WorkspaceFileDTO toFileDTO(WorkspaceFile wf) {
        WorkspaceFileDTO dto = new WorkspaceFileDTO();
        dto.setId(wf.getId());
        dto.setWorkspaceId(wf.getWorkspaceId());
        dto.setFileName(wf.getFileName());
        dto.setFileSize(wf.getFileSize());
        dto.setMinioPath(wf.getMinioPath());
        dto.setContentType(wf.getContentType());
        dto.setUploadedBy(wf.getUploadedBy());
        dto.setParentPath(wf.getParentPath());
        dto.setIsDirectory(wf.getIsDirectory());
        dto.setFileHash(wf.getFileHash());
        dto.setCreatedAt(wf.getCreatedAt() != null
                ? wf.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null);
        return dto;
    }

    private WorkspaceMemberDTO toMemberDTO(WorkspaceMember member) {
        WorkspaceMemberDTO dto = new WorkspaceMemberDTO();
        dto.setId(member.getId());
        dto.setWorkspaceId(member.getWorkspaceId());
        dto.setUserId(member.getUserId());
        dto.setRole(member.getRole());
        dto.setJoinedAt(member.getJoinedAt() != null
                ? member.getJoinedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null);
        User user = userMapper.selectById(member.getUserId());
        dto.setNickname(user != null && user.getNickname() != null ? user.getNickname() : String.valueOf(member.getUserId()));
        return dto;
    }
}
