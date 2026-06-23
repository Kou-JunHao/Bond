<template>
  <div v-loading="loading">
    <el-card v-for="group in groups" :key="group.label" shadow="never" style="margin-bottom:16px">
      <template #header>{{ group.label }}</template>
      <el-form label-width="200px" label-position="left">
        <el-form-item v-for="item in group.items" :key="item.configKey" :label="item.description || item.configKey">
          <el-input v-if="item.configType === 'string'" v-model="item.configValue" style="width:300px" />
          <el-input-number v-else-if="item.configType === 'int' || item.configType === 'long'"
                           v-model="item.configValue" :controls="false" style="width:200px" />
          <el-switch v-else-if="item.configType === 'boolean'"
                     v-model="item.configValue" active-value="true" inactive-value="false" />
          <el-button type="primary" link style="margin-left:12px" @click="saveItem(item)">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>危险操作</template>
      <el-popconfirm title="确认恢复所有配置为默认值？此操作不可撤销。" @confirm="resetAll">
        <template #reference>
          <el-button type="danger">恢复默认配置</el-button>
        </template>
      </el-popconfirm>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api/admin'

const configs = ref<any[]>([])
const loading = ref(false)

const groupMap: Record<string, string[]> = {
  '传输限制': ['transfer.'],
  '工作区限制': ['workspace.'],
  '好友系统': ['friend.', 'user.allow_register'],
  'NAT / Relay': ['nat.', 'relay.'],
  '系统基础': ['system.']
}

const groups = computed(() => {
  return Object.entries(groupMap).map(([label, prefixes]) => ({
    label,
    items: configs.value.filter(c => prefixes.some(p => c.configKey.startsWith(p) || c.configKey === p))
  })).filter(g => g.items.length > 0)
})

async function loadConfigs() {
  loading.value = true
  try {
    const res: any = await adminApi.getConfig()
    if (res.code === 200) configs.value = res.data || []
  } finally { loading.value = false }
}

async function saveItem(item: any) {
  try {
    await adminApi.updateConfig(item.configKey, String(item.configValue))
    ElMessage.success('已保存')
  } catch { ElMessage.error('保存失败') }
}

async function resetAll() {
  try {
    await adminApi.resetConfig()
    ElMessage.success('已恢复默认配置')
    loadConfigs()
  } catch { ElMessage.error('操作失败') }
}

onMounted(loadConfigs)
</script>
