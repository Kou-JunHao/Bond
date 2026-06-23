# Bond

跨网络文件传输桌面应用，支持局域网直传和云端中继传输。

## 功能特性

- **局域网设备发现** - UDP 广播 + 组播自动发现同网段设备
- **局域网直连传输** - 同网段设备间 TCP 直传，无需经过服务器
- **跨网云端中继** - 不同网段设备通过 MinIO 云端中继传输
- **端到端加密** - ECDH 密钥交换 + AES-256-GCM 分片加密
- **断点续传** - 分片级进度持久化，中断后可恢复
- **多文件多设备** - 支持批量文件同时发送给多个设备
- **用户认证** - 注册/登录/JWT 令牌管理
- **设备管理** - 云端注册设备、心跳保活、公钥管理
- **工作区** - 共享文件空间，支持多成员协作
- **传输请求审批** - 接收方可审批/拒绝 incoming 传输请求
- **密码保护** - 设备可设密码，需密码才能发送文件
- **悬浮球 UI** - 始终置顶的悬浮球，拖拽/吸附/展开动画
- **系统托盘** - 后台运行，托盘菜单控制
- **防火墙自动配置** - 检测并自动添加防火墙规则

## 技术栈

| 层级 | 客户端 | 后端 |
|------|--------|------|
| 语言 | C# (.NET 10) | Java 21 |
| 框架 | Avalonia 12.0.4 | Spring Boot 3.4.5 |
| UI 库 | SukiUI 7.0.1 | — |
| 微服务 | — | Spring Cloud 2024.0.1 |
| 服务发现 | — | Nacos 2023.0.3.2 |
| 网关 | — | Spring Cloud Gateway |
| ORM | — | MyBatis-Plus 3.5.11 |
| 数据库 | — | MySQL 8 |
| 对象存储 | — | MinIO 8.6.0 |
| 认证 | JWT (客户端解码) | JWT (jjwt 0.12.6) |
| 加密 | ECDH + AES-GCM (.NET 内置) | ECDH (Java 标准库) |

## 项目结构

```
Bond/
├── BondClient/          # C# Avalonia 桌面客户端
├── BondServer/          # Java Spring Boot 微服务后端
│   ├── bond-common/     # 公共模块（实体、DTO、工具类）
│   ├── bond-auth/       # 认证服务（端口 8081）
│   ├── bond-gateway/    # API 网关（端口 8080）
│   └── bond-transfer/   # 传输服务（端口 8082）
├── admin-web/           # Vue 3 管理后台
├── deploy/              # Docker Compose + Nginx 配置
└── sql/                 # 数据库初始化脚本
```

## 快速开始

### 本地开发

**后端：**
```bash
cd BondServer
mvn package -DskipTests
docker compose -f deploy/docker-compose.yml up -d
```

**客户端：**
```bash
cd BondClient
dotnet run
```

**管理后台：**
```bash
cd admin-web
pnpm install
pnpm dev
```

### 生产部署

详见 [SERVER_DEPLOY.md](SERVER_DEPLOY.md)

## API 文档

详见 [BondServer/API_DOCS.md](BondServer/API_DOCS.md)

## 许可证

[GNU General Public License v3](LICENSE)
