package com.bond.transfer.controller;

import com.bond.common.dto.SoftwarePackageDTO;
import com.bond.common.dto.SoftwareReleaseDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.service.SoftwareService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 客户端软件分发接口（task-81）：只暴露已发布（published=true）的包/版本。
 *
 * <p>不需要预览令牌，登录用户即可访问（由网关 + InternalAuthFilter 保证身份）。</p>
 */
@RestController
@RequestMapping("/api/software")
@RequiredArgsConstructor
public class SoftwareController {

    private final SoftwareService softwareService;

    @GetMapping("/packages")
    public Result<List<SoftwarePackageDTO>> packages() {
        return Result.ok(softwareService.listPackages());
    }

    @GetMapping("/packages/{code}/releases")
    public Result<List<SoftwareReleaseDTO>> releases(
            @PathVariable String code,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return Result.ok(softwareService.listReleases(code, channel, limit));
    }

    @GetMapping("/releases/{id}")
    public Result<SoftwareReleaseDTO> release(@PathVariable Long id) {
        return Result.ok(softwareService.getRelease(id));
    }

    /** 下载（支持单段 Range）；响应绝不包含对象存储路径。 */
    @GetMapping("/releases/{id}/download")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        SoftwareService.DownloadStream download;
        try {
            download = softwareService.openDownload(id, range);
        } catch (SoftwareService.RangeNotSatisfiableException e) {
            return ResponseEntity.<InputStreamResource>status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.totalSize())
                    .build();
        }

        long end = download.offset() + download.length() - 1;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(download.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (download.partial()) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                    "bytes " + download.offset() + "-" + end + "/" + download.fileSize());
        }
        return builder.body(new InputStreamResource(download.stream()));
    }
}
