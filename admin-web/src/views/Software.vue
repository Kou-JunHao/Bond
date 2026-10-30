<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  CloudUploadOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  SearchOutlined,
  StopOutlined
} from '@ant-design/icons-vue'
import { softwareApi } from '../api/software'
import type { SoftwarePackage, SoftwareRelease } from '../types'
import { copyText, formatBytes, formatDateTime } from '../utils/format'
import { errorText, formatApiError, isEndpointMissing } from '../utils/errors'
import {
  CHANNEL_OPTIONS,
  PLATFORM_OPTIONS,
  RELEASE_NOTES_MAX,
  channelLabel,
  matchPackage,
  packageDeleteText,
  packageMetaText,
  platformLabel,
  releaseSizeText,
  isPublished,
  releaseStatus,
  shortSha256,
  sortPackages,
  sortReleases,
  latestPublishedRelease,
  suggestBetaVersion,
  uploadPercent,
  validatePackageCode,
  validateVersion
} from '../utils/software'
import { FRONTEND_HASH_LIMIT, hashSkipHint, isValidSha256, sha256Hex } from '../utils/hash'

interface UploadState {
  code: string
  file: File | null
  version: string
  channel: string
  notes: string
  shaInput: string
  shaAuto: string
  shaState: 'idle' | 'hashing' | 'done' | 'error'
  shaError: string
  publish: boolean
  percent: number
  phase: 'idle' | 'uploading' | 'publishing'
  error: string
  result: SoftwareRelease | null
}

const packages = ref<SoftwarePackage[]>([])
const loading = ref(false)
const loadError = ref('')
const endpointMissing = ref(false)
const keyword = ref('')

const expandedKeys = ref<string[]>([])
const releasesByCode = ref<Record<string, SoftwareRelease[]>>({})
const releasesLoading = ref<Record<string, boolean>>({})
const releasesError = ref<Record<string, string>>({})

const pkgModalOpen = ref(false)
const pkgSaving = ref(false)
const pkgError = ref('')
const pkgMode = ref<'create' | 'edit'>('create')
const pkgForm = reactive({ code: '', name: '', description: '', platform: 'any' })

const uploadOpen = ref(false)
const uploadState = reactive<UploadState>({
  code: '',
  file: null,
  version: '',
  channel: 'stable',
  notes: '',
  shaInput: '',
  shaAuto: '',
  shaState: 'idle',
  shaError: '',
  publish: true,
  percent: 0,
  phase: 'idle',
  error: '',
  result: null
})

const filteredPackages = computed(() => packages.value.filter((p) => matchPackage(p, keyword.value)))

const uploadHint = computed(() => (uploadState.file ? hashSkipHint(uploadState.file.size) : null))
const uploadTotalText = computed(() =>
  uploadState.file ? `共 ${formatBytes(uploadState.file.size)}` : '未选择文件'
)

function resetUpload() {
  uploadState.code = ''
  uploadState.file = null
  uploadState.version = ''
  uploadState.channel = 'stable'
  uploadState.notes = ''
  uploadState.shaInput = ''
  uploadState.shaAuto = ''
  uploadState.shaState = 'idle'
  uploadState.shaError = ''
  uploadState.publish = true
  uploadState.percent = 0
  uploadState.phase = 'idle'
  uploadState.error = ''
  uploadState.result = null
}

async function loadPackages(keepExpanded = true) {
  loading.value = true
  loadError.value = ''
  endpointMissing.value = false
  try {
    const res = await softwareApi.listPackages()
    if (res.code === 200) {
      packages.value = sortPackages(res.data ?? [])
      if (!keepExpanded) expandedKeys.value = []
      // 已展开的包同步刷新版本列表
      for (const code of expandedKeys.value) await loadReleases(code)
    } else {
      loadError.value = res.message || `加载失败（code=${res.code}）`
    }
  } catch (e) {
    if (isEndpointMissing(e)) {
      endpointMissing.value = true
    } else {
      loadError.value = errorText(e, '加载软件包失败')
    }
  } finally {
    loading.value = false
  }
}

async function loadReleases(code: string) {
  releasesLoading.value = { ...releasesLoading.value, [code]: true }
  releasesError.value = { ...releasesError.value, [code]: '' }
  try {
    const res = await softwareApi.listReleases(code)
    if (res.code === 200) {
      releasesByCode.value = { ...releasesByCode.value, [code]: sortReleases(res.data ?? []) }
    } else {
      releasesError.value = { ...releasesError.value, [code]: res.message || `加载失败（code=${res.code}）` }
    }
  } catch (e) {
    releasesError.value = { ...releasesError.value, [code]: errorText(e, '加载版本列表失败') }
  } finally {
    releasesLoading.value = { ...releasesLoading.value, [code]: false }
  }
}

function onExpand(expanded: boolean, record: SoftwarePackage) {
  if (expanded) {
    if (!expandedKeys.value.includes(record.code)) expandedKeys.value = [...expandedKeys.value, record.code]
    if (!releasesByCode.value[record.code]) void loadReleases(record.code)
  } else {
    expandedKeys.value = expandedKeys.value.filter((c) => c !== record.code)
  }
}

// ── 下载最新已发布版本 ─────────────────────────────────────────────
// 客户端下载接口需要 JWT（匿名 401），不能用裸 <a href>；走 axios 拿 blob 再触发下载。
const downloading = ref<Record<string, boolean>>({})

function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function releaseFileName(pkg: SoftwarePackage, rel: SoftwareRelease): string {
  return rel.fileName || `${pkg.code}-${rel.version}`
}

/** 错误响应在 responseType=blob 下也是 Blob，需要读出来解析后端 JSON 的 message */
async function blobErrorMessage(e: unknown): Promise<string> {
  const maybe = e as { response?: { data?: unknown } }
  const data = maybe?.response?.data
  if (data instanceof Blob) {
    try {
      const text = await data.text()
      try {
        const parsed = JSON.parse(text) as { message?: string }
        if (parsed?.message) return parsed.message
      } catch {
        /* 不是 JSON 就回退原文片段 */
      }
      return text.slice(0, 300) || '下载失败'
    } catch {
      return '下载失败'
    }
  }
  return formatApiError(e, '下载失败')
}

async function downloadLatest(pkg: SoftwarePackage) {
  if (downloading.value[pkg.code]) return
  downloading.value = { ...downloading.value, [pkg.code]: true }
  try {
    const res = await softwareApi.listReleases(pkg.code)
    if (res.code !== 200) {
      message.error(res.message || `获取版本列表失败（code=${res.code}）`)
      return
    }
    const rel = latestPublishedRelease(res.data ?? [], pkg.latestVersion)
    if (!rel) {
      message.warning('该软件包还没有已发布的版本，发布后才能下载')
      return
    }
    const blob = await softwareApi.downloadReleaseBlob(rel.id)
    const name = releaseFileName(pkg, rel)
    triggerBlobDownload(blob, name)
    message.success(`已开始下载 ${name}`)
  } catch (e) {
    message.error(await blobErrorMessage(e))
  } finally {
    downloading.value = { ...downloading.value, [pkg.code]: false }
  }
}

function openCreate() {
  pkgMode.value = 'create'
  pkgForm.code = ''
  pkgForm.name = ''
  pkgForm.description = ''
  pkgForm.platform = 'any'
  pkgError.value = ''
  pkgModalOpen.value = true
}

function openEdit(record: SoftwarePackage) {
  pkgMode.value = 'edit'
  pkgForm.code = record.code
  pkgForm.name = record.name
  pkgForm.description = record.description ?? ''
  pkgForm.platform = record.platform || 'any'
  pkgError.value = ''
  pkgModalOpen.value = true
}

async function savePackage() {
  pkgError.value = ''
  const code = pkgForm.code.trim()
  const name = pkgForm.name.trim()
  if (!name) {
    pkgError.value = '名称不能为空'
    return
  }
  if (pkgMode.value === 'create') {
    const codeError = validatePackageCode(code)
    if (codeError) {
      pkgError.value = codeError
      return
    }
  }
  pkgSaving.value = true
  try {
    const res =
      pkgMode.value === 'create'
        ? await softwareApi.createPackage({
            code,
            name,
            description: pkgForm.description.trim(),
            platform: pkgForm.platform
          })
        : await softwareApi.updatePackage(code, {
            name,
            description: pkgForm.description.trim(),
            platform: pkgForm.platform
          })
    if (res.code === 200) {
      pkgModalOpen.value = false
      message.success(pkgMode.value === 'create' ? `已新建软件包 ${code}` : `已保存 ${code}`)
      await loadPackages()
    } else {
      pkgError.value = res.message || `保存失败（code=${res.code}）`
    }
  } catch (e) {
    pkgError.value = errorText(e, pkgMode.value === 'create' ? '新建软件包失败' : '保存软件包失败')
  } finally {
    pkgSaving.value = false
  }
}

function deletePackageText(record: SoftwarePackage) {
  const base = `删除软件包「${record.name}」（${record.code}）？`
  const meta = packageMetaText(record)
  const suffix = '该操作不可撤销；若包下还有版本，后端会拒绝并原样返回 409 提示。'
  return `${base}\n${meta}\n\n${suffix}`
}

function removePackage(record: SoftwarePackage) {
  Modal.confirm({
    title: '确认删除软件包？',
    content: deletePackageText(record),
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await softwareApi.deletePackage(record.code)
        if (res.code === 200 || res.code === 204) {
          message.success(`已删除 ${record.code}`)
          await loadPackages()
          return
        }
        if (res.code === 409) {
          message.error(packageDeleteText(409, res.message))
          return
        }
        message.error(res.message || `删除失败（code=${res.code}）`)
      } catch (e) {
        const status = Number((e as { response?: { status?: number } })?.response?.status)
        if (status === 409) {
          message.error(packageDeleteText(409, errorText(e, '')))
        } else {
          message.error(errorText(e, '删除软件包失败'))
        }
      }
    }
  })
}

function openUpload(record: SoftwarePackage) {
  resetUpload()
  uploadState.code = record.code
  uploadState.version = ''
  uploadState.channel = 'stable'
  uploadOpen.value = true
}

async function onFilePicked(file: File) {
  uploadState.file = file
  uploadState.shaInput = ''
  uploadState.shaAuto = ''
  uploadState.shaState = 'idle'
  uploadState.shaError = ''
  if (file.size > FRONTEND_HASH_LIMIT) return false
  uploadState.shaState = 'hashing'
  try {
    const buf = await file.arrayBuffer()
    uploadState.shaAuto = await sha256Hex(buf)
    uploadState.shaState = 'done'
  } catch (e) {
    uploadState.shaState = 'error'
    uploadState.shaError = errorText(e, '前端计算 SHA-256 失败')
  }
  return false
}

function copySha(sha?: string | null) {
  if (!sha) return
  void copyText(sha)
  message.success('SHA-256 已复制')
}

async function doUpload() {
  uploadState.error = ''
  if (!uploadState.file) {
    uploadState.error = '请选择要上传的文件'
    return
  }
  const versionError = validateVersion(uploadState.version)
  if (versionError) {
    uploadState.error = versionError
    return
  }
  if (uploadState.notes.length > RELEASE_NOTES_MAX) {
    uploadState.error = `更新说明不能超过 ${RELEASE_NOTES_MAX} 字`
    return
  }
  const provided = uploadState.shaInput.trim()
  if (provided && !isValidSha256(provided)) {
    uploadState.error = 'SHA-256 必须是 64 位十六进制字符串'
    return
  }
  if (provided && uploadState.shaAuto && provided.toLowerCase() !== uploadState.shaAuto.toLowerCase()) {
    uploadState.error = '填写的 SHA-256 与前端计算值不一致，请检查文件或留空由服务端计算'
    return
  }
  uploadState.phase = 'uploading'
  uploadState.percent = 0
  try {
    const res = await softwareApi.uploadRelease(
      {
        packageCode: uploadState.code,
        version: uploadState.version.trim(),
        channel: uploadState.channel,
        releaseNotes: uploadState.notes.trim() || undefined,
        sha256: provided || undefined,
        file: uploadState.file
      },
      (percent) => {
        uploadState.percent = percent
      }
    )
    if (res.code !== 200 && res.code !== 201) {
      uploadState.phase = 'idle'
      uploadState.error = res.message || `上传失败（code=${res.code}）`
      return
    }
    uploadState.result = res.data ?? null
    uploadState.percent = 100
    if (uploadState.publish && uploadState.result?.id) {
      uploadState.phase = 'publishing'
      const pub = await softwareApi.publishRelease(uploadState.result.id)
      if (pub.code !== 200 && pub.code !== 204) {
        uploadState.phase = 'idle'
        uploadState.error = `上传成功，但发布失败：${pub.message || `code=${pub.code}`}`
        return
      }
    }
    uploadState.phase = 'idle'
    message.success(uploadState.publish ? '版本已上传并发布' : '版本已上传（未发布）')
    await loadPackages()
    if (!expandedKeys.value.includes(uploadState.code)) {
      expandedKeys.value = [...expandedKeys.value, uploadState.code]
    }
    await loadReleases(uploadState.code)
    uploadOpen.value = false
  } catch (e) {
    uploadState.phase = 'idle'
    uploadState.error = errorText(e, '上传版本失败')
  }
}

function publishRelease(record: SoftwareRelease) {
  Modal.confirm({
    title: `发布版本 ${record.version}？`,
    content: `发布「${record.fileName || record.version}」后客户端即可下载该版本。此操作可通过「下架」撤回。`,
    okText: '发布',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await softwareApi.publishRelease(record.id)
        if (res.code === 200 || res.code === 204) {
          message.success(`已发布 ${record.version}`)
          await loadReleases(record.packageCode)
          await loadPackages()
        } else {
          message.error(res.message || `发布失败（code=${res.code}）`)
        }
      } catch (e) {
        message.error(errorText(e, '发布失败'))
      }
    }
  })
}

function unpublishRelease(record: SoftwareRelease) {
  Modal.confirm({
    title: `下架版本 ${record.version}？`,
    content: `下架「${record.fileName || record.version}」后客户端将无法再下载该版本（已下载的文件不受影响）。`,
    okText: '下架',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await softwareApi.unpublishRelease(record.id)
        if (res.code === 200 || res.code === 204) {
          message.success(`已下架 ${record.version}`)
          await loadReleases(record.packageCode)
          await loadPackages()
        } else {
          message.error(res.message || `下架失败（code=${res.code}）`)
        }
      } catch (e) {
        message.error(errorText(e, '下架失败'))
      }
    }
  })
}

function deleteRelease(record: SoftwareRelease) {
  Modal.confirm({
    title: `删除版本 ${record.version}？`,
    content: `删除「${record.fileName || record.version}」后不可恢复；已发布的版本删除后客户端将无法下载该版本。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await softwareApi.deleteRelease(record.id)
        if (res.code === 200 || res.code === 204) {
          message.success(`已删除 ${record.version}`)
          await loadReleases(record.packageCode)
          await loadPackages()
        } else {
          message.error(res.message || `删除失败（code=${res.code}）`)
        }
      } catch (e) {
        message.error(errorText(e, '删除版本失败'))
      }
    }
  })
}

function suggestVersion(code: string) {
  const record = packages.value.find((p) => p.code === code)
  if (!record) return
  uploadState.version = suggestBetaVersion(record.latestVersion)
  if (record.latestVersion && !record.latestVersion.includes('-')) {
    uploadState.channel = 'beta'
  }
}

const columns = [
  { title: 'Code', dataIndex: 'code', key: 'code', width: 140, ellipsis: true },
  { title: '名称', dataIndex: 'name', key: 'name', width: 185, ellipsis: true },
  { title: '平台', dataIndex: 'platform', key: 'platform', width: 100 },
  { title: '最新版本', dataIndex: 'latestVersion', key: 'latestVersion', width: 110 },
  { title: '版本数', dataIndex: 'releaseCount', key: 'releaseCount', width: 80 },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 150 },
  { title: '操作', key: 'action', width: 345 }
]

const releaseColumns = [
  { title: '版本', dataIndex: 'version', key: 'version', width: 110 },
  { title: '渠道', dataIndex: 'channel', key: 'channel', width: 80 },
  { title: '文件', dataIndex: 'fileName', key: 'fileName', width: 180, ellipsis: true },
  { title: '大小', dataIndex: 'fileSize', key: 'fileSize', width: 100 },
  { title: 'SHA-256', dataIndex: 'sha256', key: 'sha256', width: 190 },
  { title: '状态', key: 'status', width: 90 },
  { title: '发布时间', dataIndex: 'publishedAt', key: 'publishedAt', width: 145 },
  { title: '下载', dataIndex: 'downloadCount', key: 'downloadCount', width: 80 },
  { title: '操作', key: 'action', width: 172 }
]

onMounted(loadPackages)
</script>

<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>软件分发</h2>
        <div class="sub">
          管理客户端安装包与版本 · 展开行查看全部版本（含未发布）· 发布后客户端可下载
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="loadPackages()">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新建包
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="endpointMissing"
      type="warning"
      show-icon
      class="mb16"
      message="软件分发接口未上线（404）"
      description="GET /api/admin/software/packages 返回 404，通常是后端未部署到该版本。接口可用后点「刷新」生效；本页不做假数据。"
    />
    <a-alert
      v-else-if="loadError"
      type="error"
      show-icon
      class="mb16"
      :message="`加载软件包失败：${loadError}`"
    >
      <template #action>
        <a-button size="small" @click="loadPackages()">重试</a-button>
      </template>
    </a-alert>

    <div class="filter-bar">
      <a-input v-model:value="keyword" placeholder="搜索 code / 名称 / 平台" allow-clear style="max-width: 300px">
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="grow" />
      <span class="muted small">共 {{ filteredPackages.length }} / {{ packages.length }} 个软件包</span>
    </div>

    <div class="content-card table-card">
      <a-skeleton v-if="loading && !packages.length" active :paragraph="{ rows: 6 }" />
      <a-table
        v-else
        :columns="columns"
        :data-source="filteredPackages"
        row-key="code"
        :pagination="false"
        :loading="loading"
        :expandable="{ expandedRowKeys: expandedKeys }"
        size="middle"
        @expand="onExpand"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'code'">
            <span class="mono">{{ record.code }}</span>
          </template>

          <template v-else-if="column.key === 'name'">
            {{ record.name }}
            <div v-if="record.description" class="cell-sub" :title="record.description">
              {{ record.description }}
            </div>
          </template>

          <template v-else-if="column.key === 'platform'">
            {{ platformLabel(record.platform) }}
          </template>

          <template v-else-if="column.key === 'latestVersion'">
            <a-tag v-if="record.latestVersion" color="blue" class="mono">{{ record.latestVersion }}</a-tag>
            <span v-else class="muted">—</span>
          </template>

          <template v-else-if="column.key === 'releaseCount'">
            {{ record.releaseCount }} 个版本
          </template>

          <template v-else-if="column.key === 'updatedAt'">
            {{ formatDateTime(record.updatedAt) }}
          </template>

          <template v-else-if="column.key === 'action'">
            <div class="row-actions">
              <a-tooltip title="下载最新已发布版本（带登录态，失败显示后端原文）">
                <a-button type="link" size="small" :loading="!!downloading[record.code]" @click="downloadLatest(record)">
                  <template #icon><DownloadOutlined /></template>
                  下载
                </a-button>
              </a-tooltip>
              <a-button type="link" size="small" @click="openUpload(record)">
                <template #icon><CloudUploadOutlined /></template>
                上传版本
              </a-button>
              <a-button type="link" size="small" @click="openEdit(record)">
                <template #icon><EditOutlined /></template>
                编辑
              </a-button>
              <a-button type="link" size="small" danger @click="removePackage(record)">
                <template #icon><DeleteOutlined /></template>
                删除
              </a-button>
            </div>
          </template>
        </template>

        <template #expandedRowRender="{ record }">
          <div class="release-panel">
            <div class="release-head">
              <span class="section-title">版本列表</span>
              <span class="muted small">全部版本（含未发布）；发布后客户端可见</span>
              <span class="grow" />
              <a-button size="small" :loading="releasesLoading[record.code]" @click="loadReleases(record.code)">
                <template #icon><ReloadOutlined /></template>
                刷新版本
              </a-button>
            </div>

            <a-alert
              v-if="releasesError[record.code]"
              type="error"
              show-icon
              class="mb12"
              :message="`加载版本失败：${releasesError[record.code]}`"
            />
            <a-skeleton v-else-if="releasesLoading[record.code] && !releasesByCode[record.code]" active :paragraph="{ rows: 4 }" />
            <a-table
              v-else
              :columns="releaseColumns"
              :data-source="releasesByCode[record.code] || []"
              row-key="id"
              :pagination="false"
              size="small"
              class="release-table"
            >
              <template #bodyCell="{ column, record: rel }">
                <template v-if="column.key === 'version'">
                  <a-tag :color="releaseStatus(rel.publishedAt).color">{{ rel.version }}</a-tag>
                </template>
                <template v-else-if="column.key === 'channel'">
                  {{ channelLabel(rel.channel) }}
                </template>
                <template v-else-if="column.key === 'fileSize'">
                  {{ releaseSizeText(rel.fileSize) }}
                </template>
                <template v-else-if="column.key === 'sha256'">
                  <span v-if="rel.sha256" class="sha-cell">
                    <span class="mono" :title="rel.sha256">{{ shortSha256(rel.sha256, 10) }}</span>
                    <a-button type="text" size="small" @click="copySha(rel.sha256)">
                      <template #icon><CopyOutlined /></template>
                    </a-button>
                  </span>
                  <span v-else class="muted">—</span>
                </template>
                <template v-else-if="column.key === 'status'">
                  <a-tag :color="releaseStatus(rel.publishedAt).color">
                    {{ releaseStatus(rel.publishedAt).label }}
                  </a-tag>
                </template>
                <template v-else-if="column.key === 'publishedAt'">
                  {{ rel.publishedAt ? formatDateTime(rel.publishedAt) : '—' }}
                </template>
                <template v-else-if="column.key === 'action'">
                  <div class="row-actions">
                    <a-button v-if="!isPublished(rel.publishedAt)" type="link" size="small" @click="publishRelease(rel)">
                      <template #icon><RocketOutlined /></template>
                      发布
                    </a-button>
                    <a-button v-else type="link" size="small" @click="unpublishRelease(rel)">
                      <template #icon><StopOutlined /></template>
                      下架
                    </a-button>
                    <a-button type="link" size="small" danger @click="deleteRelease(rel)">
                      <template #icon><DeleteOutlined /></template>
                      删除
                    </a-button>
                  </div>
                </template>
              </template>
              <template #emptyText>
                <div class="empty-block">
                  <div class="cell-main">{{ endpointMissing ? '接口未上线，暂无版本' : '还没有上传任何版本' }}</div>
                  <div class="cell-sub">点击右上角「上传版本」添加第一个版本</div>
                </div>
              </template>
            </a-table>
          </div>
        </template>

        <template #emptyText>
          <div class="empty-block">
            <div class="cell-main">{{ keyword ? '没有匹配的软件包' : '还没有上架任何应用' }}</div>
            <div class="cell-sub">{{ keyword ? '换个关键词试试' : '点击右上角「新建包」创建第一个软件包' }}</div>
          </div>
        </template>
      </a-table>
    </div>

    <!-- 新建 / 编辑包 -->
    <a-modal
      v-model:open="pkgModalOpen"
      :title="pkgMode === 'create' ? '新建软件包' : `编辑软件包 · ${pkgForm.code}`"
      :confirm-loading="pkgSaving"
      ok-text="保存"
      cancel-text="取消"
      @ok="savePackage"
    >
      <a-form layout="vertical">
        <a-form-item
          label="Code"
          required
          :help="pkgMode === 'create' ? '小写字母开头，可含数字与 -，2~64 位；创建后不可修改' : 'Code 不可修改'"
        >
          <a-input v-model:value="pkgForm.code" :disabled="pkgMode === 'edit'" placeholder="bond-app" class="mono" />
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="pkgForm.name" placeholder="Bond 客户端" allow-clear />
        </a-form-item>
        <a-form-item label="平台">
          <a-select v-model:value="pkgForm.platform" :options="PLATFORM_OPTIONS" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="pkgForm.description" :rows="3" placeholder="可选：这个应用是做什么的" />
        </a-form-item>
      </a-form>
      <a-alert v-if="pkgError" type="error" show-icon message="保存失败（后端返回）">
        <template #description>
          <pre class="err-pre">{{ pkgError }}</pre>
        </template>
      </a-alert>
    </a-modal>

    <!-- 上传版本 -->
    <a-modal
      v-model:open="uploadOpen"
      :title="`上传版本 · ${uploadState.code}`"
      :width="620"
      :confirm-loading="uploadState.phase !== 'idle'"
      :ok-button-props="{ disabled: uploadState.phase !== 'idle' }"
      ok-text="上传"
      cancel-text="关闭"
      @ok="doUpload"
    >
      <a-form layout="vertical">
        <a-form-item label="安装包文件" required>
          <div class="upload-row">
            <a-upload :before-upload="onFilePicked" :show-upload-list="false">
              <a-button>
                <template #icon><CloudUploadOutlined /></template>
                选择文件
              </a-button>
            </a-upload>
            <span v-if="uploadState.file" class="muted small file-name" :title="uploadState.file.name">
              {{ uploadState.file.name }} · {{ uploadTotalText }}
            </span>
            <span v-else class="muted small">未选择文件</span>
          </div>
          <div v-if="uploadState.file" class="sha-box">
            <div class="sha-line">
              <span class="meta-label">前端 SHA-256</span>
              <span v-if="uploadState.shaState === 'hashing'" class="muted small">计算中…</span>
              <template v-else-if="uploadState.shaState === 'done'">
                <span class="mono sha-text">{{ uploadState.shaAuto }}</span>
                <a-button type="text" size="small" @click="copySha(uploadState.shaAuto)">
                  <template #icon><CopyOutlined /></template>
                </a-button>
              </template>
              <span v-else-if="uploadState.shaState === 'error'" class="danger-text">{{ uploadState.shaError }}</span>
              <span v-else class="muted small">{{ uploadHint }}</span>
            </div>
            <div class="muted small">最终以服务端计算结果为准</div>
          </div>
        </a-form-item>

        <a-row :gutter="16">
          <a-col :span="14">
            <a-form-item label="版本号" required :help="'语义化版本，如 1.2.0 或 1.2.0-beta.1'">
              <a-input v-model:value="uploadState.version" placeholder="1.2.0" class="mono" />
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="渠道">
              <a-select v-model:value="uploadState.channel" :options="CHANNEL_OPTIONS" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="SHA-256（可选，留空由服务端计算）">
          <a-input v-model:value="uploadState.shaInput" placeholder="64 位十六进制；与前端计算值不一致会阻止提交" class="mono" />
        </a-form-item>

        <a-form-item :label="`更新说明（不超过 ${RELEASE_NOTES_MAX} 字）`">
          <a-textarea
            v-model:value="uploadState.notes"
            :rows="3"
            :maxlength="RELEASE_NOTES_MAX"
            show-count
            placeholder="本次更新内容"
          />
        </a-form-item>

        <a-checkbox v-model:checked="uploadState.publish">上传成功后立即发布（客户端可见）</a-checkbox>

        <a-form-item class="mt12">
          <a-button size="small" type="link" @click="suggestVersion(uploadState.code)">
            根据最新版本推荐版本号
          </a-button>
        </a-form-item>

        <div v-if="uploadState.phase !== 'idle' || uploadState.percent > 0" class="progress-box">
          <a-progress :percent="uploadState.percent" :status="uploadState.phase === 'uploading' ? 'active' : 'normal'" />
          <div class="muted small">
            {{ uploadState.phase === 'publishing' ? '上传完成，正在发布…' : uploadState.phase === 'uploading' ? '上传中…' : '上传完成' }}
            <template v-if="uploadState.file">
              · 已上传 {{ formatBytes(Math.round(uploadState.file.size * (uploadState.percent / 100))) }} / {{ formatBytes(uploadState.file.size) }}
            </template>
          </div>
        </div>
      </a-form>
      <a-alert v-if="uploadState.error" type="error" show-icon message="上传失败（后端返回）">
        <template #description>
          <pre class="err-pre">{{ uploadState.error }}</pre>
        </template>
      </a-alert>
    </a-modal>
  </div>
</template>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.mt12 {
  margin-top: 12px;
}
.grow {
  flex: 1;
}
.head-actions {
  display: flex;
  gap: 10px;
}
.sub {
  color: var(--app-text-secondary);
  font-size: 13px;
  margin-top: 4px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.table-card {
  padding: 0;
  overflow: hidden;
}
.release-panel {
  padding: 4px 0 12px;
}
.release-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.section-title {
  font-weight: 600;
  font-size: 13px;
}
.release-table :deep(.ant-table) {
  background: transparent;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 0;
  flex-wrap: nowrap;
}
.row-actions :deep(.ant-btn) {
  padding: 0 4px;
}
.sha-cell {
  display: flex;
  align-items: center;
  gap: 2px;
}
.sha-text {
  word-break: break-all;
}
.upload-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 380px;
}
.sha-box {
  margin-top: 8px;
  background: var(--app-bg);
  border: 1px solid var(--app-border);
  border-radius: 6px;
  padding: 8px 10px;
}
.sha-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 2px;
}
.meta-label {
  color: var(--app-text-secondary);
  flex: none;
}
.progress-box {
  margin-top: 10px;
}
.err-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  max-height: 180px;
  overflow: auto;
}
.empty-block {
  padding: 18px 0;
}
.cell-main {
  font-size: 13px;
}
.cell-sub {
  color: var(--app-text-secondary);
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.danger-text {
  color: #cf1322;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: var(--app-text-secondary);
}
.small {
  font-size: 12px;
}
</style>
