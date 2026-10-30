# Bond

Bond 是一个自托管的「网盘 + 工作区 + 轻聊天 + 在线文档」一体化应用，包含三部分：

| 目录 | 说明 | 技术栈 |
|---|---|---|
| [`BondClient/`](BondClient/) | Windows 桌面客户端（网盘、工作区、传输、设备、聊天、笔记/文档、设置） | .NET 10 · Avalonia 12 · SukiUI 7 · CommunityToolkit.Mvvm |
| [`BondServer/`](BondServer/) | 后端服务（网关 / 认证 / 传输与存储 / 聊天 / 文档 / 管理接口） | Java 21 · Spring Boot 3.4 · Spring Cloud Gateway · PostgreSQL 16 · Flyway · RustFS(MinIO 兼容) |
| [`admin-web/`](admin-web/) | 管理后台（用户/档位/内容/运营/系统设置/日志） | Vue 3 · TypeScript · Vite · Ant Design Vue |

> 客户端早期版本目录名为 `BondClient`，现已由 `BondClient/` 取代；本仓库只保留当前客户端。

## 功能概览

- **文件与空间**：个人空间 / 团队工作区、拖拽与分片上传、断点续传、秒传、回收站（按档位保留期）、文件搜索、预览（图片/PDF/Office/视频）、WebDAV 挂载。
- **分享**：单文件与文件夹分享（文件夹打包 ZIP 下载）、提取码、有效期、下载次数上限、撤销/编辑、匿名落地页。
- **工作区**：成员与角色（owner/管理员/成员）、邀请链接、**文件级 ACL**（可限制某成员只能看到/写入指定目录或文件）。
- **传输与设备**：传输中心、局域网设备发现与直连传输、挂载网络硬盘。
- **聊天**：会话列表、消息实时推送（WebSocket，断线降级轮询）、图片/文件消息、附件下载与预览。
- **在线文档 / 笔记**：富文本编辑器（WebView2 + Quill）、Markdown 导入导出、PDF 导出、版本历史。
- **公告**：客户端未读公告铃铛与管理后台公告发布。
- **账号与商业化**：注册/登录/验证码、邮箱、头像、档位与权益、流量/空间计费口径、软件更新分发。

## 仓库结构

```
BondClient/        # Windows 客户端（.NET 10 + Avalonia）
  Assets/       # 图标、字体（MiSans，OFL）、离线编辑器静态资源
  Core/         # 形态变换动画内核、JWT 工具
  Models/ Services/ ViewModels/ Views/
  installer/    # Inno Setup 安装脚本（在仓库根 installer/ 下）
BondServer/     # 后端多模块 Maven 工程
  bond-common/ bond-auth/ bond-gateway/ bond-transfer/
  docs/         # 契约、设计与排障文档
admin-web/      # Vue 3 管理后台源码
installer/      # Windows 安装包脚本与图形资源
scripts/        # 构建辅助脚本
tools/          # 开发辅助工具
```

## 构建

### 客户端（Windows）

```powershell
dotnet build BondClient/Bond.csproj -c Release
# 自包含发布 + 安装包（需要 .NET 10 SDK 与 Inno Setup 6）
pwsh scripts/build-installer.ps1 -Version 2.1.0
```

客户端默认连接的服务端地址可在「设置 → 服务端地址」修改。

### 后端

```bash
cd BondServer
./mvnw -B -ntp -DskipTests package   # Windows 可用 build.cmd
```

需要 PostgreSQL 16+ 与 S3 兼容对象存储（RustFS / MinIO / Garage 等）。
**所有敏感配置都必须通过环境变量注入**（数据库密码、JWT 密钥、网关内部签名密钥、对象存储凭据等），仓库内只有占位与开发默认值；详见 `BondServer/deploy/.env.example`（如不存在则以各 `application*.yml` 中的 `${ENV:default}` 为准）。

Flyway 会在服务启动时自动执行 `bond-common/src/main/resources/db/migration/` 下的迁移。

### 管理后台

```bash
cd admin-web
pnpm install
pnpm run dev      # 本地开发（默认代理到后端）
pnpm run build    # 产物在 dist/
```

## 安全说明

- 本仓库**不包含任何生产凭据**：无 SSH 私钥、无数据库/对象存储密码、无 JWT 密钥、无 API Token。
- 生产环境请使用随机生成的环境变量，并确保 `.env` 等文件不入库（仓库根 `.gitignore` 已忽略）。
- 发现安全问题请勿公开提交 Issue，按 `SECURITY.md` 的方式私下反馈。

## 许可

见 [LICENSE](LICENSE)。第三方字体 MiSans 使用 OFL 许可，见 `BondClient/Assets/fonts/`。
