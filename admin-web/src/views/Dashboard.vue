<template>
  <div>
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <el-card shadow="never" class="stat-card">
          <div class="stat-icon" :style="{ background: s.bg }">
            <el-icon :size="20" color="#fff"><component :is="s.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ s.value }}</div>
            <div class="stat-label">{{ s.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>传输趋势（近7天）</template>
          <div ref="chartRef" style="height:320px"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>系统状态</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="总用户数">{{ data.totalUsers }}</el-descriptions-item>
            <el-descriptions-item label="在线设备">{{ data.activeUsers }}</el-descriptions-item>
            <el-descriptions-item label="总传输数">{{ data.totalTransfers }}</el-descriptions-item>
            <el-descriptions-item label="已完成">{{ data.completedTransfers }}</el-descriptions-item>
            <el-descriptions-item label="工作区数">{{ data.totalWorkspaces }}</el-descriptions-item>
            <el-descriptions-item label="存储用量">{{ formatSize(data.totalStorageUsed) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { adminApi } from '../api/admin'

const chartRef = ref<HTMLElement>()
const data = reactive<any>({
  totalUsers: 0, activeUsers: 0, totalTransfers: 0,
  completedTransfers: 0, totalWorkspaces: 0, totalStorageUsed: 0,
  transferTrend: {}
})

const stats = computed(() => [
  { label: '总用户', value: data.totalUsers, icon: 'User', bg: '#409eff' },
  { label: '在线设备', value: data.activeUsers, icon: 'Monitor', bg: '#67c23a' },
  { label: '总传输', value: data.totalTransfers, icon: 'Upload', bg: '#e6a23c' },
  { label: '工作区', value: data.totalWorkspaces, icon: 'FolderOpened', bg: '#909399' }
])

function formatSize(bytes: number) {
  if (!bytes) return '0 B'
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + ' GB'
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1024).toFixed(1) + ' KB'
}

function renderChart(trend: Record<string, number>) {
  if (!chartRef.value) return
  const chart = echarts.init(chartRef.value)
  const dates = Object.keys(trend)
  const values = Object.values(trend)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: dates, boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      type: 'line',
      data: values,
      smooth: true,
      areaStyle: { color: '#409eff', opacity: 0.1 },
      lineStyle: { color: '#409eff', width: 2 },
      itemStyle: { color: '#409eff' }
    }]
  })
  window.addEventListener('resize', () => chart.resize())
}

onMounted(async () => {
  try {
    const res: any = await adminApi.dashboard()
    if (res.code === 200 && res.data) {
      Object.assign(data, res.data)
      await nextTick()
      renderChart(data.transferTrend || {})
    }
  } catch {}
})
</script>

<style scoped>
.stat-row .el-col { margin-bottom: 0; }
.stat-card { display: flex; align-items: center; gap: 16px; }
.stat-card :deep(.el-card__body) { display: flex; align-items: center; gap: 16px; padding: 20px; width: 100%; }
.stat-icon { width: 48px; height: 48px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-value { font-size: 24px; font-weight: 700; color: #303133; }
.stat-label { font-size: 13px; color: #909399; margin-top: 4px; }
</style>
