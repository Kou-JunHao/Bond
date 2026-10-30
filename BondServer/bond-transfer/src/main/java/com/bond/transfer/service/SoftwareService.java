package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bond.common.dto.SoftwarePackageDTO;
import com.bond.common.dto.SoftwareReleaseDTO;
import com.bond.common.entity.SoftwarePackage;
import com.bond.common.entity.SoftwareRelease;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.SoftwarePackageMapper;
import com.bond.transfer.mapper.SoftwareReleaseMapper;
import com.bond.transfer.preview.RangeParser;
import com.bond.transfer.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 项目内软件分发（task-81）。
 *
 * <p>文件本体直接进对象存储（键 {@code software/{code}/{version}/{fileName}}），与网盘配额、
 * 下行流量计量完全隔离（发布者上传不计流量、下载不计流量）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SoftwareService {

    static final long DEFAULT_MAX_RELEASE_BYTES = 2L * 1024 * 1024 * 1024;
    static final String MAX_RELEASE_BYTES_KEY = "software.max_release_bytes";
    static final int DEFAULT_LIST_LIMIT = 50;
    static final int MAX_LIST_LIMIT = 200;

    private static final Set<String> PLATFORMS = Set.of("windows", "macos", "linux", "android", "any");
    private static final Set<String> CHANNELS = Set.of("stable", "beta", "nightly");
    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9._+\\-]{1,64}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private final SoftwarePackageMapper packageMapper;
    private final SoftwareReleaseMapper releaseMapper;
    private final ObjectStorage objectStorage;
    private final ConfigService configService;

    /** 一次下载的流与长度信息（partial=true 表示 Range 命中部分内容）。 */
    public record DownloadStream(InputStream stream, long fileSize, long offset, long length,
                                 boolean partial, String fileName) {
    }

    /** Range 越界（控制器映射为 416 + Content-Range: bytes * / total）。 */
    public static class RangeNotSatisfiableException extends RuntimeException {
        private final long totalSize;

        public RangeNotSatisfiableException(long totalSize) {
            super("range not satisfiable");
            this.totalSize = totalSize;
        }

        public long totalSize() {
            return totalSize;
        }
    }

    // ------------------------------------------------------------------ 客户端

    public List<SoftwarePackageDTO> listPackages() {
        List<SoftwarePackage> packages = packageMapper.selectPublished();
        Map<Long, Map<String, Object>> stats = statsByPackage(false);
        List<SoftwarePackageDTO> out = new ArrayList<>(packages.size());
        for (SoftwarePackage p : packages) {
            Map<String, Object> s = stats.get(p.getId());
            out.add(new SoftwarePackageDTO(p.getCode(), p.getName(), p.getDescription(), p.getPlatform(),
                    latestVersion(s), s == null ? 0L : asLong(s.get("total")), p.getUpdatedAt()));
        }
        return out;
    }

    public List<SoftwareReleaseDTO> listReleases(String packageCode, String channel, int limit) {
        SoftwarePackage pkg = requirePackage(packageCode);
        String normalizedChannel = normalizeChannel(channel);
        int clamped = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIST_LIMIT : limit, MAX_LIST_LIMIT));
        return releaseMapper.selectPublishedByPackage(pkg.getId(), normalizedChannel, clamped).stream()
                .map(r -> toDTO(r, pkg.getCode()))
                .toList();
    }

    public SoftwareReleaseDTO getRelease(Long releaseId) {
        SoftwareRelease release = requireRelease(releaseId);
        if (!Boolean.TRUE.equals(release.getPublished())) {
            throw new BusinessException(404, "版本不存在或未发布");
        }
        return toDTO(release, requirePackageById(release.getPackageId()).getCode());
    }

    /**
     * 打开下载流（支持 Range）。
     *
     * <p>每次成功打开记录一次 download_count；Range 请求同样计数。</p>
     */
    public DownloadStream openDownload(Long releaseId, String rangeHeader) {
        SoftwareRelease release = requireRelease(releaseId);
        if (!Boolean.TRUE.equals(release.getPublished())) {
            throw new BusinessException(404, "版本不存在或未发布");
        }
        long total = release.getFileSize() == null ? 0L : release.getFileSize();
        RangeParser.Spec spec = RangeParser.parse(rangeHeader, total);
        if (spec.unsatisfiable()) {
            throw new RangeNotSatisfiableException(total);
        }
        InputStream stream;
        try {
            stream = spec.partial()
                    ? objectStorage.getObject(release.getMinioPath(), spec.offset(), spec.length())
                    : objectStorage.getObject(release.getMinioPath());
        } catch (RuntimeException e) {
            log.error("open software download failed: releaseId={} key={}: {}", releaseId,
                    release.getMinioPath(), e.getMessage());
            throw new BusinessException(503, "文件暂时不可用，请稍后重试");
        }
        releaseMapper.incrementDownloadCount(release.getId());
        return new DownloadStream(stream, total, spec.offset(), spec.length(), spec.partial(),
                release.getFileName());
    }

    // ------------------------------------------------------------------ 管理端

    public List<SoftwarePackageDTO> adminListPackages() {
        List<SoftwarePackage> packages = packageMapper.selectAllOrdered();
        Map<Long, Map<String, Object>> stats = statsByPackage(true);
        List<SoftwarePackageDTO> out = new ArrayList<>(packages.size());
        for (SoftwarePackage p : packages) {
            Map<String, Object> s = stats.get(p.getId());
            out.add(new SoftwarePackageDTO(p.getCode(), p.getName(), p.getDescription(), p.getPlatform(),
                    latestVersion(s), s == null ? 0L : asLong(s.get("total")), p.getUpdatedAt()));
        }
        return out;
    }

    /**
     * 管理端：该包下全部版本（含未发布），按 createdAt DESC、id DESC 排序。
     *
     * <p>不分页（单包版本量有限），仅用 limit 保护；不返回对象存储路径。</p>
     */
    public List<SoftwareReleaseDTO> adminListReleases(String packageCode, int limit) {
        SoftwarePackage pkg = requirePackage(packageCode);
        int clamped = Math.max(1, Math.min(limit <= 0 ? MAX_LIST_LIMIT : limit, MAX_LIST_LIMIT));
        return releaseMapper.selectAllByPackage(pkg.getId(), clamped).stream()
                .map(r -> toDTO(r, pkg.getCode()))
                .toList();
    }

    public SoftwarePackageDTO createPackage(String code, String name, String description,
                                            String platform, Long adminId) {
        String normalizedCode = normalizeCode(code);
        String normalizedName = trimToNull(name);
        if (normalizedName == null) {
            throw new BusinessException(400, "包名称不能为空");
        }
        if (normalizedName.length() > 200) {
            throw new BusinessException(400, "包名称过长");
        }
        String normalizedPlatform = normalizePlatform(platform);
        if (packageMapper.selectByCode(normalizedCode) != null) {
            throw new BusinessException(409, "该包标识已存在");
        }
        SoftwarePackage pkg = new SoftwarePackage();
        pkg.setCode(normalizedCode);
        pkg.setName(normalizedName);
        pkg.setDescription(trimToNull(description));
        pkg.setPlatform(normalizedPlatform);
        pkg.setCreatedBy(adminId);
        pkg.setCreatedAt(OffsetDateTime.now());
        pkg.setUpdatedAt(OffsetDateTime.now());
        try {
            packageMapper.insert(pkg);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "该包标识已存在");
        }
        return new SoftwarePackageDTO(pkg.getCode(), pkg.getName(), pkg.getDescription(), pkg.getPlatform(),
                null, 0L, pkg.getUpdatedAt());
    }

    /**
     * 管理端：修改包的元数据（code 不可改）。
     *
     * <p>三个字段都可选；全部缺省（或空 body）→ 400，避免出现「调用了但什么都没改」的静默成功。
     * name 显式传空串 → 400（包名不能清空）；description/platform 传空串表示清空/回落到 any。</p>
     */
    public SoftwarePackageDTO updatePackage(String code, String name, String description, String platform) {
        SoftwarePackage pkg = requirePackage(code);
        boolean changed = false;
        // task-106：用显式 UpdateWrapper（列名用字符串），而不是 updateById——后者会跳过 null，
        // 导致 description 传空串「清空」时静默不生效。
        // task-107：这里刻意不用 LambdaUpdateWrapper——它需要 MyBatis-Plus 的实体 TableInfo/lambda 缓存，
        // 单测里 mapper 是 mock、没有初始化 TableInfo，会偶发
        // “can not find lambda cache for this entity” 导致全量构建不稳。
        UpdateWrapper<SoftwarePackage> update = new UpdateWrapper<>();
        update.eq("id", pkg.getId());
        if (name != null) {
            String normalizedName = trimToNull(name);
            if (normalizedName == null) {
                throw new BusinessException(400, "包名称不能为空");
            }
            if (normalizedName.length() > 200) {
                throw new BusinessException(400, "包名称过长");
            }
            pkg.setName(normalizedName);
            update.set("name", normalizedName);
            changed = true;
        }
        if (description != null) {
            String normalizedDescription = trimToNull(description);
            pkg.setDescription(normalizedDescription);
            update.set("description", normalizedDescription);
            changed = true;
        }
        if (platform != null) {
            String normalizedPlatform = normalizePlatform(platform);
            pkg.setPlatform(normalizedPlatform);
            update.set("platform", normalizedPlatform);
            changed = true;
        }
        if (!changed) {
            throw new BusinessException(400, "没有需要修改的字段");
        }
        OffsetDateTime now = OffsetDateTime.now();
        pkg.setUpdatedAt(now);
        update.set("updated_at", now);
        packageMapper.update(null, update);
        Map<String, Object> stats = statsByPackage(true).get(pkg.getId());
        return new SoftwarePackageDTO(pkg.getCode(), pkg.getName(), pkg.getDescription(), pkg.getPlatform(),
                latestVersion(stats), stats == null ? 0L : asLong(stats.get("total")), pkg.getUpdatedAt());
    }

    /**
     * 管理端：删除包（只删元数据，不涉及对象存储）。
     *
     * <p>仍有 release（含未发布）时 409：先删版本再删包，避免出现「孤立 release」。</p>
     */
    public void deletePackage(String code) {
        SoftwarePackage pkg = requirePackage(code);
        Long releaseCount = releaseMapper.selectCount(
                new LambdaQueryWrapper<SoftwareRelease>().eq(SoftwareRelease::getPackageId, pkg.getId()));
        if (releaseCount != null && releaseCount > 0L) {
            throw new BusinessException(409, "请先删除该包下所有 release");
        }
        packageMapper.deleteById(pkg.getId());
    }

    /**
     * 上传安装包：流式算 SHA-256，写对象存储，落 software_releases 行（默认未发布）。
     *
     * @param expectedSha256 可选；非空时与计算值比对，不一致 400
     */
    public SoftwareReleaseDTO uploadRelease(String packageCode, String version, String channel,
                                            String releaseNotes, String expectedSha256,
                                            MultipartFile file, Long adminId) {
        SoftwarePackage pkg = requirePackage(packageCode);
        String normalizedVersion = normalizeVersion(version);
        String normalizedChannel = normalizeChannel(channel);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "缺少安装包文件");
        }
        long maxBytes = configService.getLong(MAX_RELEASE_BYTES_KEY, DEFAULT_MAX_RELEASE_BYTES);
        if (maxBytes <= 0) {
            maxBytes = DEFAULT_MAX_RELEASE_BYTES;
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String expected = trimToNull(expectedSha256);
        if (expected != null && !SHA256_PATTERN.matcher(expected).matches()) {
            throw new BusinessException(400, "sha256 格式不正确");
        }
        if (releaseMapper.selectByVersion(pkg.getId(), normalizedVersion, normalizedChannel) != null) {
            throw new BusinessException(409, "该版本已存在");
        }

        Path temp = null;
        long size;
        String sha256;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            temp = Files.createTempFile("bond-software-", ".bin");
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            size = Files.size(temp);
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            log.error("read software upload failed: {}", e.getMessage());
            throw new BusinessException(400, "安装包读取失败");
        }

        try {
            if (size <= 0) {
                throw new BusinessException(400, "安装包不能为空");
            }
            if (size > maxBytes) {
                throw new BusinessException(413, "安装包超过服务端上限（" + (maxBytes / 1024 / 1024) + "MB）");
            }
            if (expected != null && !expected.equalsIgnoreCase(sha256)) {
                throw new BusinessException(400, "sha256 校验不一致");
            }

            String key = "software/" + pkg.getCode() + "/" + normalizedVersion + "/" + fileName;
            try (InputStream in = Files.newInputStream(temp)) {
                objectStorage.putObject(key, in, size, "application/octet-stream");
            } catch (IOException e) {
                throw new IllegalStateException("cannot re-read temp file", e);
            } catch (RuntimeException e) {
                log.error("store software release failed: key={}: {}", key, e.getMessage());
                throw new BusinessException(503, "文件存储暂时不可用，请稍后重试");
            }

            SoftwareRelease release = new SoftwareRelease();
            release.setPackageId(pkg.getId());
            release.setVersion(normalizedVersion);
            release.setChannel(normalizedChannel);
            release.setFileName(fileName);
            release.setFileSize(size);
            release.setSha256(sha256);
            release.setMinioPath(key);
            release.setReleaseNotes(trimToNull(releaseNotes));
            release.setPublished(false);
            release.setDownloadCount(0L);
            release.setCreatedBy(adminId);
            release.setCreatedAt(OffsetDateTime.now());
            try {
                releaseMapper.insert(release);
            } catch (DuplicateKeyException e) {
                safeDelete(key);
                throw new BusinessException(409, "该版本已存在");
            } catch (RuntimeException e) {
                safeDelete(key);
                log.error("insert software release failed: {}", e.getMessage());
                throw new BusinessException(500, "版本记录保存失败");
            }
            log.info("software release uploaded: package={} version={} channel={} size={} sha256={}",
                    pkg.getCode(), normalizedVersion, normalizedChannel, size, sha256);
            return toDTO(release, pkg.getCode());
        } finally {
            deleteTemp(temp);
        }
    }

    public SoftwareReleaseDTO publishRelease(Long releaseId) {
        SoftwareRelease release = requireRelease(releaseId);
        if (!Boolean.TRUE.equals(release.getPublished())) {
            // task-106：显式 UPDATE（published_at=now()），与下架成对，避免 updateById 跳过 null 的坑
            releaseMapper.publishById(releaseId);
            release.setPublished(true);
            release.setPublishedAt(OffsetDateTime.now());
        }
        return toDTO(release, requirePackageById(release.getPackageId()).getCode());
    }

    public SoftwareReleaseDTO unpublishRelease(Long releaseId) {
        SoftwareRelease release = requireRelease(releaseId);
        if (Boolean.TRUE.equals(release.getPublished())) {
            // task-106：必须显式置 published_at=NULL——updateById 会跳过 null，曾导致下架后 DB 仍是已发布态
            releaseMapper.unpublishById(releaseId);
            release.setPublished(false);
            release.setPublishedAt(null);
        }
        return toDTO(release, requirePackageById(release.getPackageId()).getCode());
    }

    public void deleteRelease(Long releaseId) {
        SoftwareRelease release = requireRelease(releaseId);
        releaseMapper.deleteById(releaseId);
        safeDelete(release.getMinioPath());
    }

    // ------------------------------------------------------------------ 内部

    private Map<Long, Map<String, Object>> statsByPackage(boolean includeUnpublished) {
        List<Map<String, Object>> rows = includeUnpublished
                ? releaseMapper.statsByPackageAll()
                : releaseMapper.statsByPackage();
        Map<Long, Map<String, Object>> out = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object pid = row.get("package_id");
            if (pid instanceof Number n) {
                out.put(n.longValue(), row);
            }
        }
        return out;
    }

    private static String latestVersion(Map<String, Object> stats) {
        if (stats == null) {
            return null;
        }
        Object v = stats.get("latest_version");
        return v == null ? null : v.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private SoftwarePackage requirePackage(String code) {
        String normalized = normalizeCode(code);
        SoftwarePackage pkg = packageMapper.selectByCode(normalized);
        if (pkg == null) {
            throw new BusinessException(404, "包不存在");
        }
        return pkg;
    }

    private SoftwarePackage requirePackageById(Long packageId) {
        SoftwarePackage pkg = packageMapper.selectById(packageId);
        if (pkg == null) {
            throw new BusinessException(404, "包不存在");
        }
        return pkg;
    }

    private SoftwareRelease requireRelease(Long releaseId) {
        SoftwareRelease release = releaseId == null ? null : releaseMapper.selectById(releaseId);
        if (release == null) {
            throw new BusinessException(404, "版本不存在或未发布");
        }
        return release;
    }

    private SoftwareReleaseDTO toDTO(SoftwareRelease r, String packageCode) {
        return new SoftwareReleaseDTO(r.getId(), packageCode, r.getVersion(), r.getChannel(), r.getFileName(),
                r.getFileSize(), r.getSha256(), r.getReleaseNotes(), r.getDownloadCount(),
                r.getPublishedAt(), r.getCreatedAt(), r.getPublished());
    }

    static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(value).matches()) {
            throw new BusinessException(400, "包标识只能是 2-64 位小写字母/数字/连字符");
        }
        return value;
    }

    static String normalizeVersion(String version) {
        String value = version == null ? "" : version.trim();
        if (!VERSION_PATTERN.matcher(value).matches()) {
            throw new BusinessException(400, "版本号格式不正确");
        }
        return value;
    }

    static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "stable";
        }
        String value = channel.trim().toLowerCase(Locale.ROOT);
        if (!CHANNELS.contains(value)) {
            throw new BusinessException(400, "渠道只能是 stable/beta/nightly");
        }
        return value;
    }

    static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "any";
        }
        String value = platform.trim().toLowerCase(Locale.ROOT);
        if (!PLATFORMS.contains(value)) {
            throw new BusinessException(400, "平台只能是 windows/macos/linux/android/any");
        }
        return value;
    }

    /** 去掉客户端路径与控制字符，只保留最后一段文件名。 */
    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "release.bin";
        }
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void safeDelete(String key) {
        try {
            objectStorage.deleteObject(key);
        } catch (RuntimeException e) {
            log.warn("delete software object failed: key={}: {}", key, e.getMessage());
        }
    }

    private static void deleteTemp(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            // 临时文件留给系统清理
        }
    }
}
