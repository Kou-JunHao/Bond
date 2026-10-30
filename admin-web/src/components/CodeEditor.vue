<template>
  <div class="code-editor" :style="{ height }">
    <div ref="gutterRef" class="code-gutter" aria-hidden="true">
      <div v-for="n in lineCount" :key="n" class="code-ln">{{ n }}</div>
    </div>
    <textarea
      ref="taRef"
      class="code-area"
      :value="modelValue"
      :placeholder="placeholder"
      :readonly="readonly"
      spellcheck="false"
      @input="onInput"
      @scroll="onScroll"
      @focus="$emit('focus')"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    height?: string
    placeholder?: string
    readonly?: boolean
  }>(),
  { height: '320px', placeholder: '', readonly: false }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'focus'): void
}>()

const taRef = ref<HTMLTextAreaElement | null>(null)
const gutterRef = ref<HTMLElement | null>(null)

const lineCount = computed(() => Math.max(1, (props.modelValue || '').split('\n').length))

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
}

function onScroll() {
  if (gutterRef.value && taRef.value) gutterRef.value.scrollTop = taRef.value.scrollTop
}

/** 供父组件插入变量：在光标处插入文本并保持焦点 */
function insertAtCursor(text: string) {
  const ta = taRef.value
  if (!ta) return
  const start = ta.selectionStart ?? ta.value.length
  const end = ta.selectionEnd ?? start
  const v = ta.value
  emit('update:modelValue', v.slice(0, start) + text + v.slice(end))
  requestAnimationFrame(() => {
    ta.focus()
    const pos = start + text.length
    ta.setSelectionRange(pos, pos)
  })
}

defineExpose({ insertAtCursor, focus: () => taRef.value?.focus() })
</script>

<style scoped>
.code-editor {
  display: flex;
  width: 100%;
  border: 1px solid var(--app-border, #e6e0d6);
  border-radius: 8px;
  overflow: hidden;
  background: var(--app-bg, #fbf8f2);
}
.code-gutter {
  flex: 0 0 auto;
  min-width: 44px;
  padding: 10px 8px 10px 0;
  overflow: hidden;
  text-align: right;
  user-select: none;
  color: var(--app-text-secondary, #9a8f7f);
  background: rgba(128, 128, 128, 0.06);
  border-right: 1px solid var(--app-border, #e6e0d6);
  font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.code-ln {
  height: 1.7em;
  padding-right: 2px;
}
.code-area {
  flex: 1 1 auto;
  width: 100%;
  border: 0;
  outline: none;
  resize: vertical;
  padding: 10px 12px;
  background: transparent;
  color: var(--app-text, #2c2620);
  font: 12.5px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  tab-size: 2;
}
.code-area::placeholder {
  color: var(--app-text-secondary, #9a8f7f);
}
</style>
