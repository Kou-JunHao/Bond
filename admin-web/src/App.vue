<template>
  <a-config-provider :theme="themeConfig" :locale="zhCN">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { useThemeStore } from './stores/theme'
import { buildThemeConfig } from './theme'

const themeStore = useThemeStore()
themeStore.init()

const themeConfig = computed(() => buildThemeConfig(themeStore.isDark))
</script>
