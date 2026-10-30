<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>公告管理</h2>
        <div class="sub">面向客户端的系统公告 · 共 {{ total }} 条</div>
      </div>
      <div class="head-actions">
        <a-button @click="openEdit(null)">
          <template #icon><PlusOutlined /></template>
          发布公告
        </a-button>
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="endpointMissing"
      type="warning"
      show-icon
      class="mb16"
      message="公告接口不可用（404）"
      description="GET/POST/PUT/DELETE /api/admin/announcements 返回 404，通常是后端未部署到该版本。接口可用后本页会自动加载真实公告；提交失败时页面会如实提示，不会伪造成功。"
    />
    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      :message="'公告加载失败：' + loadError"
      description="这是后端返回的原始错误，未被改写；可点「重试」重新查询。"
    >
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <div class="content-card table-card">
      <a-table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        row-key="id"
        :pagination="false"
        size="middle"
        :scroll="{ x: 1100 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <div class="cell-main">{{ record.title }}</div>
            <div class="cell-sub ellipsis" style="max-width: 360px">{{ record.content }}</div>
          </template>

          <template v-else-if="column.key === 'level'">
            <a-tag :color="levelInfo(record.level).color">{{ levelInfo(record.level).label }}</a-tag>
          </template>

          <template v-else-if="column.key === 'publishedAt'">
            <span class="nowrap">{{ record.publishedAtMillis ? formatDateTime(record.publishedAtMillis) : '立即生效' }}</span>
          </template>

          <template v-else-if="column.key === 'expiresAt'">
            <span class="nowrap">{{ record.expiresAtMillis ? formatDateTime(record.expiresAtMillis) : '永久' }}</span>
          </template>

          <template v-else-if="column.key === 'readCount'">
            <!-- 字段缺失显示「—」，不要显示成「已读 0」误导 -->
            <a-tag v-if="record.readCount != null" color="default">已读 {{ record.readCount }}</a-tag>
            <span v-else class="muted">—</span>
          </template>

          <template v-else-if="column.key === 'createdAt'">
            <span class="nowrap">{{ formatDateTime(record.createdAtMillis) }}</span>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAtMillis) }}</div>
          </template>

          <template v-else-if="column.key === 'actions'">
            <a-space :size="2">
              <a-button type="link" size="small" @click="openEdit(record as Announcement)">编辑</a-button>
              <a-button type="link" size="small" danger @click="remove(record as Announcement)">删除</a-button>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty :description="endpointMissing ? '接口未上线，暂无公告' : '还没有发布过公告'">
            <template #description>
              <div class="cell-main">{{ endpointMissing ? '接口未上线，暂无公告' : '还没有发布过公告' }}</div>
              <div class="cell-sub">
                {{ endpointMissing ? '后端部署该版本后刷新页面即可管理公告' : '点击右上角「发布公告」创建第一条' }}
              </div>
            </template>
          </a-empty>
        </template>
      </a-table>

      <div class="table-foot">
        <span class="muted small">第 {{ page }} 页 · 本页 {{ items.length }} 条 · 共 {{ total }} 条</span>
        <a-pagination
          :current="page"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
          @change="onPageChange"
          @showSizeChange="onSizeChange"
        />
      </div>
    </div>

    <a-modal
      v-model:open="editOpen"
      :title="editing ? '编辑公告' : '发布公告'"
      :confirm-loading="saving"
      :ok-text="editing ? '保存' : '发布'"
      cancel-text="取消"
      @ok="save"
    >
      <a-form layout="vertical" class="mt8">
        <a-form-item label="标题" required :validate-status="titleError ? 'error' : undefined" :help="titleError">
          <a-input v-model:value="form.title" :maxlength="120" placeholder="例如：v1.2.0 版本更新" allow-clear />
        </a-form-item>
        <a-form-item label="内容" required :validate-status="contentError ? 'error' : undefined" :help="contentError">
          <a-textarea v-model:value="form.content" :rows="4" :maxlength="2000" show-count placeholder="公告正文，客户端以纯文本展示" />
        </a-form-item>
        <a-form-item label="级别">
          <a-radio-group v-model:value="form.level">
            <a-radio-button v-for="l in ANNOUNCEMENT_LEVELS" :key="l.value" :value="l.value">
              {{ l.label }}
            </a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="发布时间（留空=立即）">
              <a-input v-model:value="form.publishedAt" placeholder="2026-12-31T20:00:00+08:00" allow-clear />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="过期时间（留空=永久）">
              <a-input v-model:value="form.expiresAt" placeholder="2026-12-31T23:59:59+08:00" allow-clear />
            </a-form-item>
          </a-col>
        </a-row>
        <a-alert
          type="info"
          show-icon
          message="定时发布：填写未来的发布时间，后端在此之前不会向客户端展示；过期时间必须晚于发布时间。"
        />
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { ANNOUNCEMENT_LEVELS, levelInfo } from '../constants/plans'
import { ENDPOINT_MISSING_TEXT, errorText, isEndpointMissing } from '../utils/errors'
import { formatDateTime, fromNow } from '../utils/format'
import type { Announcement, AnnouncementUpsert } from '../types'

const loading = ref(false)
const saving = ref(false)
const items = ref<Announcement[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const endpointMissing = ref(false)
const loadError = ref('')

const editOpen = ref(false)
const editing = ref<Announcement | null>(null)
const titleError = ref('')
const contentError = ref('')
const form = reactive<{ title: string; content: string; level: string; publishedAt: string; expiresAt: string }>({
  title: '',
  content: '',
  level: 'info',
  publishedAt: '',
  expiresAt: ''
})

const columns = [
  { title: '标题 / 内容', key: 'title', width: 380 },
  { title: '级别', key: 'level', width: 80 },
  { title: '发布时间', key: 'publishedAt', width: 150 },
  { title: '过期时间', key: 'expiresAt', width: 150 },
  { title: '已读', key: 'readCount', width: 80 },
  { title: '创建时间', key: 'createdAt', width: 150 },
  { title: '操作', key: 'actions', width: 110, fixed: 'right' as const }
]

function normalizeDateTime(input: string): string | null {
  const v = input.trim()
  if (!v) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return `${v}T00:00:00+08:00`
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(v)) return `${v}+08:00`
  return v
}

function millisToIso(ms?: number | null): string {
  if (!ms) return ''
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  const off = -d.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  const oh = pad(Math.floor(Math.abs(off) / 60))
  const om = pad(Math.abs(off) % 60)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}${sign}${oh}:${om}`
}

async function load() {
  loading.value = true
  try {
    const res = await adminApi.listAnnouncements({ page: page.value, size: pageSize.value })
    if (res.code === 200) {
      items.value = res.data?.items || []
      total.value = res.data?.total || 0
      endpointMissing.value = false
      loadError.value = ''
    } else {
      loadError.value = res.message || `接口返回 code=${res.code}`
      message.error(res.message || '加载失败')
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      endpointMissing.value = true
      items.value = []
      total.value = 0
      loadError.value = ''
    } else {
      loadError.value = errorText(err, '公告加载失败')
      message.error(loadError.value)
    }
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSizeChange(_p: number, size: number) {
  pageSize.value = size
  page.value = 1
  load()
}

function openEdit(record: Announcement | null) {
  editing.value = record
  titleError.value = ''
  contentError.value = ''
  form.title = record?.title || ''
  form.content = record?.content || ''
  form.level = record?.level || 'info'
  form.publishedAt = millisToIso(record?.publishedAtMillis)
  form.expiresAt = millisToIso(record?.expiresAtMillis)
  editOpen.value = true
}

function buildBody(): AnnouncementUpsert | null {
  titleError.value = form.title.trim() ? '' : '请输入标题'
  contentError.value = form.content.trim() ? '' : '请输入内容'
  if (titleError.value || contentError.value) return null
  return {
    title: form.title.trim(),
    content: form.content.trim(),
    level: form.level,
    publishedAt: normalizeDateTime(form.publishedAt),
    expiresAt: normalizeDateTime(form.expiresAt)
  }
}

async function save() {
  const body = buildBody()
  if (!body) return
  saving.value = true
  try {
    const res = editing.value
      ? await adminApi.updateAnnouncement(editing.value.id, body)
      : await adminApi.createAnnouncement(body)
    if (res.code === 200) {
      message.success(editing.value ? '公告已更新' : '公告已发布')
      editOpen.value = false
      await load()
    } else {
      message.error(res.message || '保存失败')
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      endpointMissing.value = true
      message.warning(ENDPOINT_MISSING_TEXT)
    } else {
      message.error(errorText(err, '保存失败'))
    }
  } finally {
    saving.value = false
  }
}

function remove(record: Announcement) {
  Modal.confirm({
    title: '删除公告',
    content: `确定删除公告「${record.title}」？客户端将不再展示，该公告的已读记录也会被删除。`,
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await adminApi.deleteAnnouncement(record.id)
        if (res.code === 200) {
          message.success('公告已删除')
          await load()
        } else {
          message.error(res.message || '删除失败')
        }
      } catch (err) {
        if (isEndpointMissing(err)) {
          endpointMissing.value = true
          message.warning(ENDPOINT_MISSING_TEXT)
        } else {
          message.error(errorText(err, '删除失败'))
        }
      }
    }
  })
}

onMounted(load)
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mt8 {
  margin-top: 8px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.table-card {
  padding: 8px 8px 12px;
}
.table-card :deep(.ant-table-thead > tr > th) {
  background: transparent;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 4px;
  flex-wrap: wrap;
}
</style>
