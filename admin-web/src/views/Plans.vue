<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import type { AdminPlan, PlanLimitKey, PlanLimits } from '../types'
import { errorText, isEndpointMissing, ENDPOINT_MISSING_TEXT } from '../utils/errors'
import { bytesText, type ByteUnit } from '../utils/settings'
import {
  PLAN_LIMIT_GROUPS,
  byteLimitFromParts,
  byteLimitParts,
  byteUnitOptions,
  buildPlanPatch,
  countDraftChanges,
  formatLimitValue,
  formatPlanPrice,
  planActiveTag,
  planTitle,
  validateLimitValue,
  validatePlanDraft,
  type PlanLimitMeta
} from '../utils/plans'

const SAVE_SUCCESS_TEXT = '已保存，新删除的文件立即按新保留期计算'

const plans = ref<AdminPlan[]>([])
const loading = ref(true)
const loadError = ref('')
const drafts = reactive<Record<string, Record<string, number>>>({})
const saveErrors = reactive<Record<string, string>>({})
const saving = ref('')

const editing = ref<{ code: string; key: PlanLimitKey } | null>(null)
const editInt = ref<number | null>(null)
const editAmount = ref<number | null>(null)
const editUnit = ref<ByteUnit>('GB')
const editError = ref('')

const unitOptions = byteUnitOptions()

/** 编辑器实时预览（字节类）：数值 + 单位 → GB 等可读文本 */
const bytePreview = computed(() => {
  const bytes = byteLimitFromParts(editAmount.value, editUnit.value)
  return bytes === null ? '非法数值' : bytesText(bytes)
})

function currentValue(plan: AdminPlan, key: PlanLimitKey): number | undefined {
  const draft = drafts[plan.code]
  if (draft && key in draft) return draft[key]
  return plan.limits[key]
}

function display(plan: AdminPlan, key: PlanLimitKey) {
  return formatLimitValue(key, currentValue(plan, key))
}

function isPending(plan: AdminPlan, key: PlanLimitKey): boolean {
  return !!drafts[plan.code] && key in drafts[plan.code]
}

function isEditing(plan: AdminPlan, key: PlanLimitKey): boolean {
  return editing.value?.code === plan.code && editing.value?.key === key
}

function draftCount(plan: AdminPlan): number {
  return countDraftChanges(drafts[plan.code])
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await adminApi.listPlans()
    if (res.code === 200 && Array.isArray(res.data)) {
      plans.value = res.data
      for (const code of Object.keys(drafts)) delete drafts[code]
    } else {
      loadError.value = res.message || '加载档位失败'
    }
  } catch (e) {
    loadError.value = isEndpointMissing(e) ? ENDPOINT_MISSING_TEXT : errorText(e, '加载档位失败')
  } finally {
    loading.value = false
  }
}

function startEdit(plan: AdminPlan, meta: PlanLimitMeta) {
  saveErrors[plan.code] = ''
  editError.value = ''
  editing.value = { code: plan.code, key: meta.key }
  const value = currentValue(plan, meta.key)
  if (meta.kind === 'bytes') {
    const parts = byteLimitParts(value)
    editAmount.value = parts.value
    editUnit.value = parts.unit
  } else {
    editInt.value = typeof value === 'number' ? value : null
  }
}

function cancelEdit() {
  editing.value = null
  editError.value = ''
  editInt.value = null
  editAmount.value = null
}

function onUnitChange() {
  // 单位变化时只更新预览；数值保持不变
  editError.value = ''
}

function confirmEdit() {
  if (!editing.value) return
  const plan = plans.value.find((item) => item.code === editing.value?.code)
  if (!plan) return
  const meta = PLAN_LIMIT_GROUPS.flatMap((group) => group.metas).find(
    (item) => item.key === editing.value?.key
  )
  if (!meta) return
  let value: number | null
  if (meta.kind === 'bytes') {
    value = byteLimitFromParts(editAmount.value, editUnit.value)
    if (value === null) {
      editError.value = '字节数非法：请输入非负整数，且换算后不超出前端安全整数范围'
      return
    }
  } else {
    value = editInt.value
  }
  const error = validateLimitValue(meta.key, value)
  if (error) {
    editError.value = error
    return
  }
  const baseline = plan.limits[meta.key]
  const numeric = value as number
  if (!drafts[plan.code]) drafts[plan.code] = {}
  if (baseline === numeric) {
    delete drafts[plan.code][meta.key]
  } else {
    drafts[plan.code][meta.key] = numeric
  }
  if (draftCount(plan) === 0) delete drafts[plan.code]
  saving.value = ''
  cancelEdit()
}

function discardPlan(plan: AdminPlan) {
  delete drafts[plan.code]
  saveErrors[plan.code] = ''
  if (editing.value?.code === plan.code) cancelEdit()
}

async function savePlan(plan: AdminPlan) {
  const draft = drafts[plan.code]
  if (!draft || draftCount(plan) === 0) return
  const draftError = validatePlanDraft(plan.limits as PlanLimits, draft)
  if (draftError) {
    saveErrors[plan.code] = draftError
    return
  }
  const patch = buildPlanPatch(plan.limits as PlanLimits, draft)
  if (Object.keys(patch).length === 0) {
    delete drafts[plan.code]
    return
  }
  saving.value = plan.code
  saveErrors[plan.code] = ''
  try {
    const res = await adminApi.updatePlan(plan.code, patch)
    if (res.code === 200 && res.data) {
      const index = plans.value.findIndex((item) => item.code === plan.code)
      if (index >= 0) plans.value[index] = res.data
      delete drafts[plan.code]
      message.success(SAVE_SUCCESS_TEXT)
    } else {
      saveErrors[plan.code] = errorText({ message: res.message } as never, '保存失败')
    }
  } catch (e) {
    saveErrors[plan.code] = isEndpointMissing(e) ? ENDPOINT_MISSING_TEXT : errorText(e, '保存失败')
  } finally {
    saving.value = ''
  }
}

function tag(plan: AdminPlan) {
  return planActiveTag(plan.isActive)
}

onMounted(load)
</script>

<template>
  <div class="page-container plans-page">
    <div class="page-head">
      <div>
        <h2>档位管理</h2>
        <p class="sub">调整各档位的权益参数，保存后立即生效（档位缓存即时失效）。</p>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <a-alert
      class="mb16"
      type="info"
      show-icon
      message="本页只编辑 limits 权益参数；价格、档位名称与启用状态由套餐配置维护，保存只提交改动过的键。"
    />

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      :message="loadError"
      description="档位参数读取失败，请检查网络或稍后重试。"
      class="mb16"
    >
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <a-skeleton v-if="loading" active :paragraph="{ rows: 8 }" />

    <div v-else-if="!plans.length && !loadError" class="empty-block">
      <a-empty description="暂无档位数据" />
    </div>

    <div v-else class="plan-grid">
      <a-card v-for="plan in plans" :key="plan.code" class="plan-card" :bordered="false">
        <div class="plan-card-head">
          <div class="plan-title">
            <h3>{{ planTitle(plan) }}</h3>
            <a-tag :color="tag(plan).color">{{ tag(plan).text }}</a-tag>
            <code class="plan-code">{{ plan.code }}</code>
          </div>
          <div class="plan-price">{{ formatPlanPrice(plan.priceCents, plan.period) }}</div>
        </div>

        <a-alert
          v-if="saveErrors[plan.code]"
          type="error"
          show-icon
          closable
          class="save-error"
          :message="saveErrors[plan.code]"
          @close="delete saveErrors[plan.code]"
        />

        <div v-for="group in PLAN_LIMIT_GROUPS" :key="group.group" class="limit-group">
          <div class="group-title">{{ group.group }}</div>
          <div
            v-for="meta in group.metas"
            :key="meta.key"
            class="limit-row"
            :class="{ pending: isPending(plan, meta.key), editing: isEditing(plan, meta.key) }"
          >
            <div class="limit-label" :title="meta.hint">{{ meta.label }}</div>

            <div v-if="!isEditing(plan, meta.key)" class="limit-value-cell">
              <div class="limit-value-line">
                <span class="limit-value">{{ display(plan, meta.key).text }}</span>
                <a-tag v-if="isPending(plan, meta.key)" color="orange" class="pending-tag">待保存</a-tag>
              </div>
              <div class="limit-raw" v-if="display(plan, meta.key).rawText">
                原始值 {{ display(plan, meta.key).rawText }}
              </div>
              <div class="limit-raw" v-else-if="isPending(plan, meta.key)">
                修改前 {{ formatLimitValue(meta.key, plan.limits[meta.key]).text }}
              </div>
              <a-button
                type="text"
                size="small"
                class="edit-btn"
                :aria-label="`编辑${meta.label}`"
                @click="startEdit(plan, meta)"
              >
                <template #icon><EditOutlined /></template>
                改
              </a-button>
            </div>

            <div v-else class="limit-editor">
              <template v-if="meta.kind === 'bytes'">
                <a-input-number
                  v-model:value="editAmount"
                  :min="0"
                  :precision="0"
                  size="small"
                  class="num-input"
                  :aria-label="`${meta.label}数值`"
                  @press-enter="confirmEdit"
                />
                <a-select
                  v-model:value="editUnit"
                  :options="unitOptions"
                  size="small"
                  class="unit-select"
                  :aria-label="`${meta.label}单位`"
                  @change="onUnitChange"
                />
                <span class="byte-preview">= {{ bytePreview }}</span>
              </template>
              <template v-else>
                <a-input-number
                  v-model:value="editInt"
                  :min="meta.min"
                  :precision="0"
                  size="small"
                  class="num-input"
                  :aria-label="`${meta.label}数值`"
                  @press-enter="confirmEdit"
                />
              </template>
              <a-button type="primary" size="small" @click="confirmEdit">确认</a-button>
              <a-button size="small" @click="cancelEdit">取消</a-button>
            </div>

            <div class="limit-hint" v-if="isEditing(plan, meta.key) && editError">{{ editError }}</div>
            <div class="limit-hint zero" v-else-if="meta.zeroText">0 = {{ meta.zeroText }}</div>
          </div>
        </div>

        <div class="plan-actions">
          <span class="change-count">{{ draftCount(plan) ? `已改动 ${draftCount(plan)} 项` : '未改动' }}</span>
          <a-button size="small" :disabled="!draftCount(plan)" @click="discardPlan(plan)">放弃更改</a-button>
          <a-button
            type="primary"
            size="small"
            :disabled="!draftCount(plan)"
            :loading="saving === plan.code"
            @click="savePlan(plan)"
          >
            保存更改
          </a-button>
        </div>
      </a-card>
    </div>
  </div>
</template>

<style scoped>
.plans-page {
  padding-bottom: 24px;
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}

@media (max-width: 1280px) {
  .plan-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.plan-card {
  border-radius: var(--app-radius);
  border: 1px solid var(--app-border);
  background: var(--app-card-bg);
}

.plan-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--app-border);
}

.plan-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.plan-title h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text);
}

.plan-code {
  font-size: 12px;
  color: var(--app-text-secondary);
  background: var(--app-bg);
  border-radius: 6px;
  padding: 1px 6px;
}

.plan-price {
  font-size: 15px;
  font-weight: 600;
  color: var(--brand);
  white-space: nowrap;
}

.save-error {
  margin: 12px 0 4px;
}

.limit-group {
  margin-top: 12px;
}

.group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
  letter-spacing: 0.4px;
  margin-bottom: 4px;
}

.limit-row {
  padding: 7px 8px;
  border-radius: 8px;
  border: 1px solid transparent;
}

.limit-row + .limit-row {
  margin-top: 2px;
}

.limit-row:hover {
  background: var(--app-bg);
}

.limit-row.pending {
  background: var(--brand-bg);
  border-color: rgba(229, 113, 83, 0.35);
}

.limit-row.editing {
  background: var(--app-bg);
  border-color: var(--brand);
}

.limit-label {
  font-size: 13px;
  color: var(--app-text);
}

.limit-value-cell {
  position: relative;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 2px;
  padding-right: 44px;
}

.limit-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}

.limit-raw {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.pending-tag {
  margin-left: 2px;
}

.edit-btn {
  position: absolute;
  right: 0;
  top: -2px;
  color: var(--app-text-secondary);
  padding: 0 4px;
}

.limit-editor {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.num-input {
  width: 96px;
}

.unit-select {
  width: 76px;
}

.byte-preview {
  font-size: 12px;
  color: var(--app-text-secondary);
  min-width: 64px;
}

.limit-hint {
  margin-top: 3px;
  font-size: 12px;
  color: #d4380d;
}

.limit-hint.zero {
  color: var(--app-text-secondary);
}

.plan-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--app-border);
}

.change-count {
  margin-right: auto;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.empty-block {
  padding: 48px 0;
}
</style>
