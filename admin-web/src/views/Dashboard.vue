<template>
  <div>
    <a-row :gutter="16">
      <a-col :xs="12" :sm="12" :md="6" v-for="s in stats" :key="s.label">
        <a-card hoverable class="stat-card">
          <a-statistic :title="s.label" :value="s.value" :value-style="{ color: s.color }">
            <template #prefix><component :is="s.icon" /></template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="16" style="margin-top: 16px">
      <a-col :xs="24" :lg="16">
        <a-card title="传输趋势" size="small">
          <template #extra>
            <a-radio-group v-model:value="trendDays" size="small" @change="loadTrend">
              <a-radio-button :value="7">近7天</a-radio-button>
              <a-radio-button :value="30">近30天</a-radio-button>
            </a-radio-group>
          </template>
          <div ref="chartRef" style="height: 300px"></div>
        </a-card>
      </a-col>
      <a-col :xs="24" :lg="8">
        <a-card title="系统状态" size="small">
          <a-descriptions :column="1" size="small" bordered>
            <a-descriptions-item v-for="item in statusItems" :key="item.label" :label="item.label">{{ item.value }}</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, markRaw } from 'vue'
import * as echarts from 'echarts'
import { adminApi } from '../api/admin'
import { UserOutlined, LaptopOutlined, CloudUploadOutlined, FolderOutlined } from '@ant-design/icons-vue'

const chartRef = ref<HTMLElement>()
const trendDays = ref(7)
let chartInstance: echarts.ECharts | null = null
const data = reactive<any>({ totalUsers: 0, activeUsers: 0, totalTransfers: 0, completedTransfers: 0, totalWorkspaces: 0, totalStorageUsed: 0, transferTrend: {} })

const stats = computed(() => [
  { label: '总用户', value: data.totalUsers, icon: markRaw(UserOutlined), color: '#1677ff' },
  { label: '在线设备', value: data.activeUsers, icon: markRaw(LaptopOutlined), color: '#52c41a' },
  { label: '总传输', value: data.totalTransfers, icon: markRaw(CloudUploadOutlined), color: '#fa8c16' },
  { label: '工作区', value: data.totalWorkspaces, icon: markRaw(FolderOutlined), color: '#722ed1' }
])

const statusItems = computed(() => [
  { label: '总用户数', value: data.totalUsers },
  { label: '在线设备', value: data.activeUsers },
  { label: '总传输数', value: data.totalTransfers },
  { label: '已完成', value: data.completedTransfers },
  { label: '工作区数', value: data.totalWorkspaces },
  { label: '存储用量', value: formatSize(data.totalStorageUsed) }
])

function formatSize(bytes: number) { if (!bytes) return '0 B'; if (bytes >= 1073741824) return (bytes/1073741824).toFixed(1)+' GB'; if (bytes >= 1048576) return (bytes/1048576).toFixed(1)+' MB'; return (bytes/1024).toFixed(1)+' KB' }

function renderChart(trend: Record<string, number>) {
  if (!chartRef.value) return
  if (!chartInstance) chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: Object.keys(trend), boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'line', data: Object.values(trend), smooth: true, areaStyle: { color: '#1677ff', opacity: 0.1 }, lineStyle: { color: '#1677ff', width: 2 }, itemStyle: { color: '#1677ff' } }]
  })
}

async function loadTrend() {
  try { const res: any = await adminApi.getTransferTrend(trendDays.value); if (res.code === 200 && res.data) renderChart(res.data) } catch {}
}

function handleResize() { chartInstance?.resize() }

onMounted(async () => {
  try {
    const res: any = await adminApi.dashboard()
    if (res.code === 200 && res.data) { Object.assign(data, res.data); await nextTick(); renderChart(data.transferTrend || {}) }
  } catch {}
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => { window.removeEventListener('resize', handleResize); chartInstance?.dispose() })
</script>

<style scoped>
.stat-card { margin-bottom: 16px; }
</style>
