# 对象存储切换 / 兼容性验证清单（S3 兼容后端）

> 本次改造：移除 MinIO Java SDK（不再依赖 `minio` SDK），统一使用 **AWS SDK v2 S3**（`software.amazon.awssdk:s3`），
> 通过 `endpointOverride` + `pathStyle=true` 适配 MinIO / **RustFS** / Garage / Cloudflare R2 / B2。
> 业务 key 规则保持不变：`transfers/{id}/...`、`workspaces/{workspaceId}/...`、`avatars/{userId}...`。
> 本机无 Docker/对象存储，以下运行时用例需要在测试环境或发布后手工跑。

## 1. 切换到 RustFS 需要改哪几行配置

只需改 **`deploy/application-transfer.yml`**（生产挂载文件）里的 1~3 行：

```yaml
storage:
  endpoint: http://rustfs:9000     # ← 唯一必须改的：RustFS 地址（1panel 容器名/端口按实际填，HTTPS 也可）
  access-key: <RustFS access key>  # ← 若 RustFS 用了新凭据才需要改
  secret-key: <RustFS secret key>  # ← 同上
  bucket: bond-transfer            # 不动（沿用旧 bucket 名，key 完全不变）
  region: us-east-1                # 不动（MinIO/RustFS 不校验 region）
  path-style: true                 # 不动（RustFS 需要 path-style）
```

说明：
- 代码配置解析顺序：`storage.*` → 旧 `minio.*`（兼容，见下）→ 环境变量 `MINIO_*` → 内置默认值。
  **旧的 `minio.*` 段即使保留也能启动**，但建议按上面迁移，避免两套键并存。
- 也支持环境变量：`STORAGE_ENDPOINT / STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY / STORAGE_BUCKET /
  STORAGE_REGION / STORAGE_PATH_STYLE / STORAGE_PRESIGN_EXPIRY_SECONDS`。
- 启动时会自动 `HeadBucket/createBucket`；若 RustFS 暂不可用只记录错误、不阻塞启动（与旧 MinIO 行为一致）。
- 历史数据迁移：MinIO 已导出的对象目录 `~/bond-minio-export/bond-transfer`（777MB / 30 对象），
  用 `aws s3 sync` / `rclone` / `mc` 原样上传到 RustFS 的 `bond-transfer` bucket 即可，**不要改 key 路径**：
  ```bash
  # 示例（二选一）
  aws --endpoint-url http://<rustfs>:9000 s3 sync ~/bond-minio-export/bond-transfer s3://bond-transfer/ \
      --profile rustfs
  # 或
  rclone sync ~/bond-minio-export/bond-transfer rustfs:bond-transfer
  ```
- 切换后必须跑下面的 8 个用例再放流量。

## 2. 切换后端必须跑通的 8 个用例

| # | 用例 | 验证点 | 通过标准 |
| --- | --- | --- | --- |
| 1 | 小文件 PUT / GET | `putObject` → `getObject` 内容一致 | 字节级一致（含中文文件名/二进制） |
| 2 | >100MB 分片上传 | `initMultipart` → `uploadPart`×N（每片 ≥5MB）→ `completeMultipart` | 完整文件 GET 下来 SHA-256 与源文件一致 |
| 3 | Range 下载 | `getObject(key, offset, length)`（迁移任务按分片下载） | 返回字节数与 offset 正确，与完整文件对应区间一致 |
| 4 | 预签名 GET 有效期 | `presignedGetUrl(key, ttl)` | URL 可直接下载；超过 ttl 后返回 403/过期错误 |
| 5 | 删除 | `deleteObject` 后 `getObject`/`statObject` | 对象不存在（404），再次删除不报错 |
| 6 | 列举前缀 | `listObjects("workspaces/{id}/")` | 数量/大小与实际一致，>1000 对象时自动翻页 |
| 7 | 同名覆盖 | 对同一 key 连续 `putObject` 两次 | 后写覆盖前写（GET 到新内容），不产生重复对象 |
| 8 | 并发分片 | 同一 uploadId 并发 `uploadPart`（不同 partNumber） | 全部成功、complete 后文件完整；`abortMultipart` 能清理未完成上传 |

补充：
- 前端/客户端仍通过后端代理上传下载（`/api/files/**`、`/api/transfer/**`），**不直接访问对象存储**；
  预签名 URL 能力已抽象好，本期没有对外暴露。
- 单次 PUT 上限受 S3 规范限制（5GB）；现有最大文件 5GB，接近上限的大文件后续可切到分片上传（接口已就绪）。
- `completeMultipart` 会显式校验「除最后一片外每片 ≥ 5MB」，不满足直接抛 `IllegalArgumentException`
  （避免 RustFS/S3 端拒绝后产生不明确的 500）。

### 2.1 自动化覆盖（本机无 Docker 也能跑）

`bond-transfer/src/test/java/com/bond/transfer/storage/S3ObjectStorageWireTest.java` 用伪 `SdkHttpClient`
捕获 AWS SDK 真实发出的 HTTP 请求（含 aws-chunked 解码），不需要网络/对象存储，覆盖 Lead 未在 RustFS 验证的三项：

- `multipartUploadOver5Mb`：真实 5MB+1MB 两个 part 的 init/uploadPart/complete 请求构造与字节数（>5MB 分片）；
- `rangeDownloadSendsRangeHeader`：请求头 `Range: bytes=100-109` + 206 响应解析（range 下载）；
- `presignedGetUrlShape`：path-style bucket 路径、`X-Amz-Signature`/`X-Amz-Expires`、密钥不泄漏（预签名 URL）。

另有 `S3ObjectStorageMultipartTest` 覆盖「非末片 <5MB 拒绝」与 ETag 校验，
`StorageSettingsTest` 覆盖 storage.*/旧 minio.* 兼容与默认值，`ObjectStorageConfigTest` 真实构造 SDK client。
运行时仍建议按上表 1/4/5/6/7/8 在 RustFS 上抽样验证一次。

## 3. 缩略图缓存前缀（task-19）

- 缩略图写入 RustFS 的 `thumbnails/{fileId}-{width}.jpg`（JPEG q80），系统数据，**不计入用户空间配额**。
- 切换存储后端时该前缀随 bucket 整体迁移即可，无额外结构；`GET /api/files/{fileId}/thumbnail` 会 cache-first。
- 预览/内容流不经过预签名 URL：RustFS 保持 127.0.0.1 私网，由应用服务器流式透传（支持 Range/206）。

