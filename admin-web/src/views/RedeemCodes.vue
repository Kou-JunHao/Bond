<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>兑换码</h2>
        <div class="sub">批量生成订阅兑换码 · 后端未提供列表查询接口，生成后请立即复制保存</div>
      </div>
    </div>

    <a-alert
      type="warning"
      show-icon
      class="mb16"
      message="兑换码是一次性凭据"
      description="后端不提供历史列表查询（GET /admin/redeem-codes 不存在），本页只展示本次会话内生成的结果；刷新页面后旧兑换码将无法找回。"
    />

    <a-row :gutter="16">
      <a-col :xs="24" :xl="10">
        <div class="content-card">
          <div class="section-title">
            <h3>生成兑换码</h3>
            <span class="muted small">POST /api/admin/redeem-codes</span>
          </div>
          <a-form layout="vertical">
            <a-form-item label="档位">
              <a-radio-group v-model:value="planCode" button-style="solid">
                <a-radio-button v-for="p in PLANS" :key="p.code" :value="p.code">
                  {{ p.name }} · {{ formatBytes(p.storageBytes) }}
                </a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="时长（天）">
                  <a-input-number v-model:value="durationDays" :min="1" :max="3650" style="width: 100%" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="数量（1–500）">
                  <a-input-number v-model:value="count" :min="1" :max="500" style="width: 100%" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="兑换截止时间（可选，ISO 8601 + 时区）">
              <a-input v-model:value="expiresAt" placeholder="例如 2026-12-31T23:59:59+08:00，留空=永久" allow-clear />
            </a-form-item>
            <a-button type="primary" block :loading="generating" @click="generate">
              生成兑换码
            </a-button>
          </a-form>
        </div>
      </a-col>

      <a-col :xs="24" :xl="14">
        <div class="content-card">
          <div class="section-title">
            <h3>本次生成结果</h3>
            <span v-if="codes.length" class="muted small">{{ codes.length }} 个</span>
            <span class="grow" />
            <a-button v-if="codes.length" size="small" @click="copyAll">
              <template #icon><CopyOutlined /></template>
              复制全部
            </a-button>
          </div>

          <a-empty v-if="!codes.length" description="还没有生成兑换码">
            <template #description>
              <div class="cell-main">还没有生成兑换码</div>
              <div class="cell-sub">左侧选择档位与时长，生成后可一键复制全部分发给用户</div>
            </template>
          </a-empty>

          <div v-else class="code-list">
            <div v-for="(code, idx) in codes" :key="code" class="code-item">
              <span class="code-index">{{ idx + 1 }}</span>
              <span class="code-text mono">{{ code }}</span>
              <a-button type="link" size="small" @click="copyOne(code)">
                <template #icon><CopyOutlined /></template>
                复制
              </a-button>
            </div>
          </div>

          <a-alert
            v-if="lastBatch"
            type="info"
            show-icon
            class="mt16"
            :message="`最近一批：${planName(lastBatch.planCode)} · ${lastBatch.durationDays} 天 · ${lastBatch.createdAt}`"
            description="兑换码为一次性凭据：用户兑换后绑定到其账号，不可转移；请勿在本页刷新前丢失。"
          />
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { CopyOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { PLANS, planName } from '../constants/plans'
import { ENDPOINT_MISSING_TEXT, errorText, isEndpointMissing } from '../utils/errors'
import { formatBytes } from '../utils/format'
import type { RedeemCodesRequest } from '../types'

const planCode = ref('plus')
const durationDays = ref(30)
const count = ref(10)
const expiresAt = ref('')
const generating = ref(false)
const codes = ref<string[]>([])
const lastBatch = ref<{ planCode: string; durationDays: number; createdAt: string } | null>(null)

function normalizeDateTime(input: string): string | null {
  const v = input.trim()
  if (!v) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return `${v}T00:00:00+08:00`
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(v)) return `${v}+08:00`
  return v
}

function stamp(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function generate() {
  if (!count.value || count.value < 1) {
    message.warning('数量至少为 1')
    return
  }
  const body: RedeemCodesRequest = {
    planCode: planCode.value,
    durationDays: durationDays.value || 30,
    count: Math.min(500, Math.floor(count.value)),
    expiresAt: normalizeDateTime(expiresAt.value)
  }
  generating.value = true
  try {
    const res = await adminApi.createRedeemCodes(body)
    if (res.code === 200) {
      codes.value = Array.isArray(res.data) ? res.data : []
      lastBatch.value = { planCode: body.planCode, durationDays: body.durationDays, createdAt: stamp() }
      message.success(`已生成 ${codes.value.length} 个兑换码`)
    } else {
      message.error(res.message || '生成失败')
    }
  } catch (err) {
    message.error(isEndpointMissing(err) ? ENDPOINT_MISSING_TEXT : errorText(err, '生成失败'))
  } finally {
    generating.value = false
  }
}

async function copyOne(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    message.success('已复制')
  } catch {
    message.warning('复制失败，请手动选择文本')
  }
}

async function copyAll() {
  try {
    await navigator.clipboard.writeText(codes.value.join('\n'))
    message.success(`已复制 ${codes.value.length} 个兑换码`)
  } catch {
    message.warning('复制失败，请手动选择文本')
  }
}

</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mt16 {
  margin-top: 16px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.section-title h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.grow {
  flex: 1;
}
.code-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 460px;
  overflow: auto;
}
.code-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--app-border);
  background: rgba(0, 0, 0, 0.015);
}
.code-index {
  width: 28px;
  flex: none;
  color: var(--app-text-secondary);
  font-size: 12px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.code-text {
  flex: 1;
  font-size: 12.5px;
  word-break: break-all;
}
</style>
