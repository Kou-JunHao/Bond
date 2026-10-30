<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>工作区</h2>
        <div class="sub">
          所有用户的工作区（按创建时间倒序） · 共 {{ loadedTotal }} 个
          <span class="muted"> · 分页由服务端 LIMIT/OFFSET 执行</span>
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <div class="filter-bar">
      <a-input
        v-model:value="keyword"
        placeholder="在当前页中过滤名称 / Owner ID"
        allow-clear
        style="width: 300px"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="grow" />
      <span class="muted small">
        成员 / 文件数由列表接口实时聚合（task-102）；点「详情」再取分页明细
      </span>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      closable
      :message="loadError"
      @close="loadError = ''"
    >
      <template #description>这是后端返回的原始错误，未被改写。</template>
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <div class="content-card table-card">
      <a-table
        :columns="columns"
        :data-source="filtered"
        :loading="loading"
        row-key="id"
        :pagination="false"
        :scroll="{ x: 1120 }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <div class="cell-main">{{ record.name }}</div>
            <div class="cell-sub ellipsis" style="max-width: 320px">{{ record.description || '—' }}</div>
          </template>

          <template v-else-if="column.key === 'ownerId'">
            <span class="mono">{{ record.ownerId }}</span>
          </template>

          <template v-else-if="column.key === 'counts'">
            <a-space :size="4">
              <a-tooltip :title="countTooltip('memberCount', record as WorkspaceItem)">
                <a-tag color="default">成员 {{ record.memberCount ?? '—' }}</a-tag>
              </a-tooltip>
              <a-tooltip :title="countTooltip('fileCount', record as WorkspaceItem)">
                <a-tag color="default">文件 {{ record.fileCount ?? '—' }}</a-tag>
              </a-tooltip>
            </a-space>
          </template>

          <template v-else-if="column.key === 'usage'">
            <!-- 后端未统计时显示「未统计」，不画 0% 空进度条（避免被读成用了 0%） -->
            <span v-if="record.usedSize == null || record.maxSize == null" class="muted">未统计</span>
            <div v-else class="usage">
              <a-progress
                :percent="percent(record.usedSize, record.maxSize)"
                :show-info="false"
                size="small"
                stroke-color="#e57153"
                class="usage-bar"
              />
              <span class="mono usage-text">
                {{ formatBytes(record.usedSize) }} / {{ formatBytes(record.maxSize) }}
              </span>
            </div>
          </template>

          <template v-else-if="column.key === 'createdAt'">
            <span class="nowrap">{{ formatDateTime(record.createdAt) }}</span>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAt) }}</div>
          </template>

          <template v-else-if="column.key === 'actions'">
            <a-space :size="2">
              <a-button type="link" size="small" @click="openDetail(record as WorkspaceItem)">详情</a-button>
              <a-button type="link" size="small" danger @click="removeWorkspace(record as WorkspaceItem)">删除</a-button>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="暂无工作区">
            <template #description>
              <div class="cell-main">暂无工作区</div>
              <div class="cell-sub">用户创建团队工作区后会出现在这里；当前列表来自真实接口</div>
            </template>
          </a-empty>
        </template>
      </a-table>

      <div class="table-foot">
        <span class="muted small">当前显示 {{ filtered.length }} / 本页 {{ items.length }} 条</span>
        <a-pagination
          :current="page"
          :page-size="pageSize"
          :total="paginationTotal"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
          @change="onPageChange"
          @showSizeChange="onSizeChange"
        />
      </div>
    </div>

    <!-- 详情抽屉 -->
    <a-drawer v-model:open="detailOpen" title="工作区详情" :width="460" placement="right">
      <a-spin :spinning="detailLoading">
        <template v-if="detail">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="工作区 ID"><span class="mono">{{ detail.id }}</span></a-descriptions-item>
            <a-descriptions-item label="名称">{{ detail.name }}</a-descriptions-item>
            <a-descriptions-item label="描述">{{ detail.description || '—' }}</a-descriptions-item>
            <a-descriptions-item label="Owner ID"><span class="mono">{{ detail.ownerId || '—' }}</span></a-descriptions-item>
            <a-descriptions-item label="成员数">{{ detail.memberCount }}</a-descriptions-item>
            <a-descriptions-item label="文件数">{{ detail.fileCount }}</a-descriptions-item>
            <a-descriptions-item label="容量">
              {{ formatBytes(detail.usedSize) }} / {{ formatBytes(detail.maxSize) }}（{{ percent(detail.usedSize, detail.maxSize) }}%）
            </a-descriptions-item>
          </a-descriptions>
          <a-alert
            v-if="detailError"
            type="error"
            show-icon
            :message="detailError"
            class="mt16"
          />
        </template>
        <a-empty v-else-if="!detailLoading" description="未获取到详情" />
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { errorText } from '../utils/errors'
import { formatBytes, formatDateTime, fromNow, percent } from '../utils/format'
import type { WorkspaceDetail, WorkspaceItem } from '../types'

const loading = ref(false)
const loadError = ref('')
const items = ref<WorkspaceItem[]>([])
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loadedTotal = ref(0)

function countTooltip(field: 'memberCount' | 'fileCount', row: WorkspaceItem): string {
  const v = row[field]
  if (v === null || v === undefined) {
    return `后端该行 ${field}=null，未聚合；点「详情」查看实时聚合`
  }
  return field === 'memberCount' ? `成员数（实时聚合）：${v}` : `文件数（实时聚合）：${v}`
}

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<WorkspaceDetail | null>(null)
const detailError = ref('')

const columns = [
  { title: '工作区', key: 'name', width: 300 },
  { title: 'Owner ID', key: 'ownerId', width: 170 },
  { title: '成员 / 文件', key: 'counts', width: 150 },
  { title: '容量', key: 'usage', width: 220 },
  { title: '创建时间', key: 'createdAt', width: 160 },
  { title: '操作', key: 'actions', width: 120, fixed: 'right' as const }
]

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return items.value
  return items.value.filter(
    (w) => w.name?.toLowerCase().includes(kw) || String(w.ownerId).includes(kw)
  )
})

/** 列表接口只返回本页数据，没有 total：按「是否满页」推算是否还有下一页 */
const paginationTotal = computed(() => {
  const base = (page.value - 1) * pageSize.value + items.value.length
  return items.value.length === pageSize.value ? base + 1 : base
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await adminApi.listWorkspaces({ page: page.value, size: pageSize.value })
    if (res.code === 200) {
      items.value = res.data || []
      loadedTotal.value = paginationTotal.value
    } else {
      loadError.value = res.message || '工作区列表加载失败'
      message.error(res.message || '加载失败')
    }
  } catch (err) {
    loadError.value = errorText(err, '工作区列表加载失败')
    message.error(errorText(err, '工作区列表加载失败'))
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

async function openDetail(record: WorkspaceItem) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    const res = await adminApi.getWorkspaceDetail(record.id)
    if (res.code === 200) detail.value = res.data
    else detailError.value = res.message || '详情加载失败'
  } catch (err) {
    detailError.value = errorText(err, '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function removeWorkspace(record: WorkspaceItem) {
  Modal.confirm({
    title: '删除工作区',
    content: `确定删除工作区「${record.name}」？将同时删除其文件记录、成员关系与对象存储中的文件，且不可恢复。`,
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await adminApi.deleteWorkspace(record.id)
        if (res.code === 200) {
          message.success('工作区已删除')
          await load()
        } else {
          message.error(res.message || '删除失败')
        }
      } catch (err) {
        message.error(errorText(err, '删除失败'))
      }
    }
  })
}

onMounted(load)
</script>

<style scoped>
.small {
  font-size: 12px;
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
.usage {
  display: flex;
  align-items: center;
  gap: 10px;
}
.usage-bar {
  width: 90px;
  margin: 0;
}
.usage-text {
  font-size: 11.5px;
  color: var(--app-text-secondary);
  white-space: nowrap;
}
.mt16 {
  margin-top: 16px;
}
</style>
