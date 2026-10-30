<template>
  <div ref="el" class="trend-chart" :style="{ height: `${height}px` }" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { useThemeStore } from '../stores/theme'

const props = withDefaults(
  defineProps<{
    /** 日期 → 数量（后端 transferTrend：key = yyyy-MM-dd） */
    data: Record<string, number>
    height?: number
  }>(),
  { height: 300 }
)

const theme = useThemeStore()
const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  const keys = Object.keys(props.data || {})
  const values = keys.map((k) => props.data[k] ?? 0)
  const dark = theme.isDark
  const axisColor = dark ? '#5c636b' : '#9aa0a8'
  const splitColor = dark ? 'rgba(255,255,255,0.06)' : '#f0f1f3'
  const textColor = dark ? '#aeb4bc' : '#5b6169'

  chart.setOption(
    {
      grid: { left: 8, right: 14, top: 24, bottom: 6, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: dark ? '#232629' : '#ffffff',
        borderColor: dark ? '#2f3338' : '#e6e8eb',
        textStyle: { color: textColor, fontSize: 12 },
        axisPointer: { type: 'shadow', shadowStyle: { color: dark ? 'rgba(255,255,255,0.04)' : 'rgba(31,35,41,0.04)' } },
        formatter: (params: unknown) => {
          const first = (params as Array<{ name: string; value: number }>)[0]
          if (!first) return ''
          return `<div style="font-weight:600;margin-bottom:2px">${first.name}</div>传输任务：<b>${first.value}</b> 个`
        }
      },
      xAxis: {
        type: 'category',
        data: keys,
        boundaryGap: false,
        axisLine: { lineStyle: { color: splitColor } },
        axisTick: { show: false },
        axisLabel: { color: axisColor, fontSize: 11, formatter: (v: string) => v.slice(5) }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: splitColor } },
        axisLabel: { color: axisColor, fontSize: 11 }
      },
      series: [
        {
          name: '传输任务',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          showSymbol: values.length <= 14,
          data: values,
          lineStyle: { width: 3, color: '#e57153' },
          itemStyle: { color: '#e57153', borderColor: dark ? '#1b1d20' : '#fff', borderWidth: 2 },
          // 纯色面积（企业克制感：不使用渐变）
          areaStyle: { color: 'rgba(229,113,83,0.14)' }
        }
      ]
    },
    { notMerge: true }
  )
}

onMounted(() => {
  render()
  ro = new ResizeObserver(() => chart?.resize())
  if (el.value) ro.observe(el.value)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
  chart = null
})

watch(() => [props.data, theme.isDark], render, { deep: true })
</script>

<style scoped>
.trend-chart {
  width: 100%;
}
</style>
