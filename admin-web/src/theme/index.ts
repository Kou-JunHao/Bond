import { theme } from 'ant-design-vue'

/** 品牌色：暖陶土橙（与 Bond 客户端视觉一致） */
export const BRAND = {
  primary: '#e57153',
  primaryHover: '#f0836a',
  primaryActive: '#cf6044'
}

/**
 * ant-design-vue 主题 token。
 * 亮色：浅灰底 + 白卡片；暗色：深灰底 + 深卡片，避免纯黑刺眼。
 */
export function buildThemeConfig(isDark: boolean) {
  return {
    algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: BRAND.primary,
      colorInfo: BRAND.primary,
      colorLink: BRAND.primary,
      colorLinkHover: BRAND.primaryHover,
      borderRadius: 8,
      fontSize: 14,
      fontFamily:
        "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif",
      colorBgLayout: isDark ? '#0f1113' : '#f6f7f9',
      colorBgContainer: isDark ? '#1b1d20' : '#ffffff',
      colorBgElevated: isDark ? '#232629' : '#ffffff',
      colorBorder: isDark ? '#2f3338' : '#e6e8eb',
      colorBorderSecondary: isDark ? '#2a2d31' : '#f0f1f3',
      colorTextBase: isDark ? '#e6e8eb' : '#1f2329',
      // 占位符默认仅 25% 透明度（暗色下 ≈2.1:1），提到 45%~60% 保证 ≥4.5:1
      colorTextPlaceholder: isDark ? 'rgba(230, 232, 235, 0.6)' : 'rgba(31, 35, 41, 0.45)',
      controlItemBgActive: isDark ? 'rgba(229,113,83,0.18)' : '#fdf1ed'
    },
    components: {
      // ant-design-vue 4 的 Layout 组件 token 是 colorBgHeader/colorBgBody/colorBgTrigger，
      // 不是 antd v5 的 headerBg/siderBg（写错会导致头部回落到默认深蓝 #001529）
      Layout: {
        colorBgHeader: isDark ? '#141619' : '#ffffff',
        colorBgBody: isDark ? '#0f1113' : '#f6f7f9',
        colorBgTrigger: isDark ? '#1b1d20' : '#f6f7f9'
      },
      Menu: {
        itemBg: 'transparent',
        subMenuItemBg: 'transparent',
        itemSelectedBg: isDark ? 'rgba(229,113,83,0.18)' : '#fdf1ed',
        itemSelectedColor: isDark ? '#ff9d80' : '#d45438',
        itemHoverBg: isDark ? 'rgba(255,255,255,0.04)' : '#f5f6f8',
        itemBorderRadius: 8,
        itemMarginInline: 10,
        itemHeight: 42,
        groupTitleColor: isDark ? '#7d848d' : '#9aa0a8',
        groupTitleFontSize: 12
      },
      Table: {
        headerBg: isDark ? '#202326' : '#fafbfc',
        headerColor: isDark ? '#aeb4bc' : '#5b6169',
        rowHoverBg: isDark ? 'rgba(255,255,255,0.03)' : '#fafbfc',
        borderColor: isDark ? '#2a2d31' : '#f0f1f3',
        cellPaddingBlock: 12
      },
      Card: {
        colorBorderSecondary: isDark ? '#2a2d31' : '#f0f1f3'
      },
      Modal: {
        contentBg: isDark ? '#1b1d20' : '#ffffff',
        headerBg: isDark ? '#1b1d20' : '#ffffff'
      },
      Tabs: { itemSelectedColor: BRAND.primary },
      Tag: { defaultBg: isDark ? '#26292d' : '#f4f5f7' }
    }
  }
}
