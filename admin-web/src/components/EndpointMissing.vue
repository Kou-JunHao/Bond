<template>
  <div class="endpoint-missing">
    <a-alert type="warning" show-icon :banner="false" class="missing-alert">
      <template #message>
        <span class="missing-title">接口未上线</span>
      </template>
      <template #description>
        <div class="missing-desc">
          <p>{{ detail }}</p>
          <div class="missing-actions">
            <a-button size="small" :loading="loading" @click="$emit('retry')">
              <template #icon><ReloadOutlined /></template>
              重新检测
            </a-button>
            <a-tag v-if="status" color="default" class="mono">{{ status }}</a-tag>
          </div>
        </div>
      </template>
    </a-alert>
    <slot />
  </div>
</template>

<script setup lang="ts">
import { ReloadOutlined } from '@ant-design/icons-vue'

withDefaults(
  defineProps<{
    detail?: string
    status?: string | null
    loading?: boolean
  }>(),
  {
    detail:
      '后端对应接口尚未部署（管理端返回 404）。页面保留完整交互骨架，接口上线后即可直接使用，不会伪造成功结果。',
    status: null,
    loading: false
  }
)

defineEmits<{ retry: [] }>()
</script>

<style scoped>
.endpoint-missing + * {
  margin-top: 16px;
}
.missing-alert {
  border-radius: var(--app-radius);
  margin-bottom: 16px;
}
.missing-title {
  font-weight: 600;
}
.missing-desc p {
  margin: 4px 0 10px;
  line-height: 1.7;
  color: var(--app-text-secondary);
}
.missing-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
