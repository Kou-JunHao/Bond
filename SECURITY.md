# 安全策略

## 报告漏洞

请**不要**通过公开 Issue 报告安全问题。请通过仓库所有者公开的联系方式私下说明，包含：

- 受影响的组件与版本/提交
- 复现步骤与影响面
- 建议的修复方向（可选）

我们会确认后尽快修复，并在合适的时间公开致谢。

## 密钥与凭据

- 仓库中不包含生产密钥、私钥、令牌、数据库或对象存储密码。
- 部署所需的敏感配置一律通过环境变量注入：
  - `DB_PASSWORD`、`JWT_SECRET`、`INTERNAL_GATEWAY_SECRET`
  - `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY`
  - 可选：`PREVIEW_TOKEN_SECRET`、`SHARE_CODE_PEPPER`
- `application*.yml` 中的默认值只用于本地开发，**禁止**直接用于生产。
- 任何提交若意外包含真实凭据，请视为已泄露并立即轮换。

## 支持的版本

仅最新发布版本会获得安全修复。
