/// <reference types="vite/client" />

/** 构建期注入：package.json 的 version；空字符串表示取不到，界面不显示版本号 */
declare const __APP_VERSION__: string
/** 构建期注入：构建环境名（APP_ENV，默认 production），仅用于展示映射 */
declare const __APP_ENV__: string
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
