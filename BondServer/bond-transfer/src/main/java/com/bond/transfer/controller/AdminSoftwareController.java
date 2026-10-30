package com.bond.transfer.controller;

import com.bond.common.dto.SoftwarePackageDTO;
import com.bond.common.dto.SoftwareReleaseDTO;
import com.bond.common.dto.Result;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.audit.AdminAudit;
import com.bond.transfer.service.SoftwareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 软件分发管理端（task-81）。/api/admin/** 由 AdminInterceptor 统一做管理员守卫（非管理员 403）。
 *
 * <p>写接口打 {@code @AdminAudit}，自动写 admin_operation_logs。</p>
 */
@RestController
@RequestMapping("/api/admin/software")
@RequiredArgsConstructor
public class AdminSoftwareController {

    private final SoftwareService softwareService;

    public record CreatePackageRequest(String code, String name, String description, String platform) {
    }

    /** 三项均可选；空 body 由服务层统一 400（避免「调用了但什么都没改」的静默成功）。 */
    public record UpdatePackageRequest(String name, String description, String platform) {
    }

    @AdminAudit(action = "software.package.create", targetType = "software_package")
    @PostMapping("/packages")
    public Result<SoftwarePackageDTO> createPackage(@RequestHeader("X-User-Id") Long adminUserId,
                                                    @RequestBody CreatePackageRequest request) {
        return Result.ok(softwareService.createPackage(request.code(), request.name(),
                request.description(), request.platform(), adminUserId));
    }

    @AdminAudit(action = "software.package.update", targetType = "software_package")
    @PutMapping("/packages/{code}")
    public Result<SoftwarePackageDTO> updatePackage(@RequestHeader("X-User-Id") Long adminUserId,
                                                    @PathVariable String code,
                                                    @RequestBody(required = false) UpdatePackageRequest request) {
        if (request == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        return Result.ok(softwareService.updatePackage(code, request.name(), request.description(),
                request.platform()));
    }

    @AdminAudit(action = "software.package.delete", targetType = "software_package")
    @DeleteMapping("/packages/{code}")
    public ResponseEntity<Void> deletePackage(@RequestHeader("X-User-Id") Long adminUserId,
                                              @PathVariable String code) {
        softwareService.deletePackage(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/packages")
    public Result<List<SoftwarePackageDTO>> listPackages() {
        return Result.ok(softwareService.adminListPackages());
    }

    /**
     * 该包下全部版本（含未发布），按 createdAt DESC、id DESC 排序。
     *
     * <p>管理端需要看到未发布版本才能发布/删除；不分页，仅 limit（默认 200）保护。</p>
     */
    @GetMapping("/packages/{code}/releases")
    public Result<List<SoftwareReleaseDTO>> listReleases(@PathVariable String code,
                                                         @RequestParam(required = false, defaultValue = "200") int limit) {
        return Result.ok(softwareService.adminListReleases(code, limit));
    }

    /**
     * 上传安装包（multipart）。默认不发布，需再调 publish。
     *
     * <p>可选 {@code sha256}：传入时与流式计算值比对，不一致 400。</p>
     */
    @AdminAudit(action = "software.release.upload", targetType = "software_release")
    @PostMapping("/releases")
    public Result<SoftwareReleaseDTO> uploadRelease(
            @RequestHeader("X-User-Id") Long adminUserId,
            @RequestParam String packageCode,
            @RequestParam String version,
            @RequestParam(required = false, defaultValue = "stable") String channel,
            @RequestParam(required = false) String releaseNotes,
            @RequestParam(required = false) String sha256,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return Result.ok(softwareService.uploadRelease(packageCode, version, channel, releaseNotes,
                sha256, file, adminUserId));
    }

    @AdminAudit(action = "software.release.publish", targetType = "software_release")
    @PostMapping("/releases/{id}/publish")
    public Result<SoftwareReleaseDTO> publish(@RequestHeader("X-User-Id") Long adminUserId,
                                              @PathVariable Long id) {
        return Result.ok(softwareService.publishRelease(id));
    }

    @AdminAudit(action = "software.release.unpublish", targetType = "software_release")
    @PostMapping("/releases/{id}/unpublish")
    public Result<SoftwareReleaseDTO> unpublish(@RequestHeader("X-User-Id") Long adminUserId,
                                                @PathVariable Long id) {
        return Result.ok(softwareService.unpublishRelease(id));
    }

    @AdminAudit(action = "software.release.delete", targetType = "software_release")
    @DeleteMapping("/releases/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-User-Id") Long adminUserId,
                                       @PathVariable Long id) {
        softwareService.deleteRelease(id);
        return ResponseEntity.noContent().build();
    }
}
