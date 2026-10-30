import http from './admin'
import type {
  ApiResult,
  SoftwarePackage,
  SoftwarePackageCreate,
  SoftwarePackageUpdate,
  SoftwareRelease,
  SoftwareReleaseUpload
} from '../types'

/**
 * 软件分发管理 API（task-90；版本列表由后端 task-92 提供）。
 * 独立于 admin.ts，方便管理软件相关的类型与上传逻辑。
 *
 * 真实契约（task-90 rev4，Lead 已在生产逐个实测）：
 *  - GET    /api/admin/software/packages                         包列表（无 id/createdAt）
 *  - POST   /api/admin/software/packages                         新建包（重复 code 409）
 *  - PUT    /api/admin/software/packages/{code}                  编辑包（空 body 400）
 *  - DELETE /api/admin/software/packages/{code}                  删除包（有版本 409 / 无版本 204 / 不存在 404）
 *  - GET    /api/admin/software/packages/{code}/releases         全部版本（task-92，含未发布）
 *  - POST   /api/admin/software/releases                         multipart 上传（query + file）
 *  - POST   /api/admin/software/releases/{id}/publish|unpublish  发布/下线
 *  - DELETE /api/admin/software/releases/{id}                    删除版本（204）
 */

const asResult = <T>(p: Promise<unknown>) => p as unknown as Promise<ApiResult<T>>

export const softwareApi = {
  /** 包列表（含没有版本的包；latestVersion=null 表示无已发布版本） */
  listPackages: () => asResult<SoftwarePackage[]>(http.get('/admin/software/packages')),

  /** 新建包：重复 code 后端返回 409，message 原样展示 */
  createPackage: (body: SoftwarePackageCreate) =>
    asResult<SoftwarePackage>(http.post('/admin/software/packages', body)),

  /** 编辑包：只允许改 name/description/platform，code 不可变 */
  updatePackage: (code: string, body: SoftwarePackageUpdate) =>
    asResult<SoftwarePackage>(http.put(`/admin/software/packages/${encodeURIComponent(code)}`, body)),

  /** 删除包：有 release 时 409；无 release 时 http 204（此时 data 为空） */
  deletePackage: (code: string) =>
    asResult<void>(http.delete(`/admin/software/packages/${encodeURIComponent(code)}`)),

  /** 版本列表（task-92）：包含未发布版本，publishedAt=null 即未发布；limit=200 一次取全 */
  listReleases: (packageCode: string) =>
    asResult<SoftwareRelease[]>(
      http.get(`/admin/software/packages/${encodeURIComponent(packageCode)}/releases`, {
        params: { limit: 200 }
      })
    ),

  /**
   * 上传版本（multipart）：packageCode/version/channel/releaseNotes/sha256 走 query，文件放 form。
   * 用 onUploadProgress 做真实进度；timeout=0 避免大文件传 30s 超时。
   */
  uploadRelease: (input: SoftwareReleaseUpload, onProgress?: (percent: number, loaded: number, total: number) => void) => {
    const form = new FormData()
    form.append('file', input.file)
    return asResult<SoftwareRelease>(
      http.post('/admin/software/releases', form, {
        params: {
          packageCode: input.packageCode,
          version: input.version,
          channel: input.channel,
          ...(input.releaseNotes ? { releaseNotes: input.releaseNotes } : {}),
          ...(input.sha256 ? { sha256: input.sha256 } : {})
        },
        timeout: 0,
        onUploadProgress: (e) => {
          if (!onProgress || !e.total) return
          onProgress(Math.min(100, Math.round((e.loaded / e.total) * 100)), e.loaded, e.total)
        }
      })
    )
  },

  /** 发布版本（成功后 publishedAt 非空） */
  publishRelease: (id: string) =>
    asResult<SoftwareRelease>(http.post(`/admin/software/releases/${encodeURIComponent(id)}/publish`)),

  /** 下线版本（publishedAt 变回 null） */
  unpublishRelease: (id: string) =>
    asResult<SoftwareRelease>(http.post(`/admin/software/releases/${encodeURIComponent(id)}/unpublish`)),

  /** 删除版本（204；有下载记录是否拒绝以后端为准，message 原样展示） */
  deleteRelease: (id: string) =>
    asResult<void>(http.delete(`/admin/software/releases/${encodeURIComponent(id)}`)),

  /**
   * 下载已发布版本（客户端下载接口 `GET /api/software/releases/{id}/download`）。
   * 该接口需要 JWT（匿名 401），裸 <a href> 无法带 Authorization，
   * 所以必须走 axios 拿 blob 再由页面触发下载。
   */
  downloadReleaseBlob: (id: string) =>
    http.get(`/software/releases/${encodeURIComponent(id)}/download`, {
      responseType: 'blob',
      timeout: 0
    }) as unknown as Promise<Blob>
}

export default softwareApi
