# NewCrisp

[![CI](https://github.com/jianxiaomiao/newCrisp/actions/workflows/ci.yml/badge.svg)](https://github.com/jianxiaomiao/newCrisp/actions/workflows/ci.yml)

轻量级、多租户 Web 在线客服服务台（对标 Crisp 核心人工客服体验）。既支持通过一行 `<script>` 代码为任意外部官网挂载 Shadow DOM 悬浮对话窗，也支持作为独立服务台对接业务系统（如 Nexus）的已登录用户体系。

## 核心特性

- **对外 SaaS 官网与自助开通（`/`）**：支持租户自助注册并自动登录进入管理员工作台，内置 **2 数四则算术 SVG 验证码**（除法反推保证除数 ≥ 2 且 100% 整除）与 **SMTP 邮箱验证码**（支持 QQ 邮箱 SSL 465，60s 冷却 / 日限 / IP 限流 / 错 5 次作废）。
- **平台超级管理员控制台（`/platform.html`）**：通过 Platform Key 签发 `HttpOnly` Cookie 会话（兼容 Bearer API），支持全平台概览 KPI、租户检索与筛选、新建租户、一键停用/启用（即时吊销员工会话）、配置/轮换 Nexus 集成密钥与重置租户管理员密码。
- **一行代码外部悬浮窗（`/widget.js`）**：Shadow DOM 样式隔离、原地弹出不跳转页面；紧凑双界面设计（默认对话页输入框贴底，左上角 `<` 切入「全部/历史会话」列表且最新置顶）；支持访客接续码跨端/登录后合并历史。
- **实时客服工作台（`/staff.html`）**：
  - WebSocket 实时消息、乐观发送、已读/未读回执、正在输入提示（typing）、断线 `seq` 补拉、图片粘贴/拖拽上传与缩略图灯箱预览、Web Audio 提示音与浏览器通知；
  - 客户画像侧边栏（资料、当前浏览页面、该客户历史会话）、独立表存储的**团队内部备注**（客户不可见）、会话标签、全库搜索、`/` 快捷回复与模板变量替换；
  - 多租户 + 部门 + RBAC（管理员 / 主管 / 客服）、自动负载均衡分配、排队超时提醒、营业时间与自动欢迎语、闲置超时自动结束、转派交接备注；
  - 会话结束 **1–5 星 CSAT 满意度评价**与管理员**数据看板**。

## 技术栈

- **后端**：Java 21 · Spring Boot 4.1.1（WebMVC / WebSocket / JDBC Client / Mail）· Flyway（`V1–V13`）· MySQL 8.4
- **前端**：Vue 3 · TypeScript · Vite 8 · Element Plus（按需注册）· 独立打包的 `widget.js`
- **测试与 CI**：JUnit 5 + Mockito · Playwright + Docker MySQL 8.4 · GitHub Actions CI

## 本地构建与测试

需要 JDK 21、Maven、Node.js 20.19+ 或 22.12+、npm，运行 E2E 验收时需要运行中的 Docker。

```sh
# 安装前端依赖并构建（含 TypeScript 校验）
npm ci
npm run build

# 后端单元测试（无需启动数据库）
mvn test

# 打包完整可执行 JAR（自动构建并打包前端静态产物）
mvn package
```

### 真实 MySQL 8.4 + 浏览器端到端验收

```sh
npm ci
npx playwright install chromium --only-shell
npm run test:e2e
```

脚本会打包后端、拉起隔离的 MySQL 8.4 容器、执行 Flyway `V1–V13` 迁移并运行 Playwright 浏览器回归测试，结束后自动清理容器与数据卷。

若需联合验证真实 Nexus 账号单点登录与未读角标链路：

```sh
NEWCRISP_E2E_NEXUS_ROOT=/path/to/Nexus npm run test:e2e
```

如果 `mvn` 不在 `PATH`，可通过 `NEWCRISP_MAVEN` 指定路径；默认测试端口 `18760` 可通过 `NEWCRISP_E2E_PORT` 修改。

## 页面入口

| 路径 | 用途 |
| :--- | :--- |
| `/`（`/index.html`） | 对外 SaaS 官网首页与租户自助注册 |
| `/platform.html` | 平台超级管理员控制台（跨租户管理） |
| `/staff.html` | 租户管理员与客服工作台 |
| `/customer.html` | Nexus 已登录客户对话页（支持 `?embed=1` 紧凑嵌入） |
| `/embed/{siteId}` | 外部网站访客对话页（由 `/widget.js` 挂载） |
| `/widget.js` | 外部网站一行代码悬浮窗脚本 |

## 核心环境变量

| 变量 | 说明 | 默认值 |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | MySQL 8.4 JDBC 连接串 | — |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 | — |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | — |
| `NEWCRISP_PUBLIC_ORIGIN` | 对外公开来源（生产环境须为 HTTPS） | `http://127.0.0.1:8080` |
| `NEWCRISP_PLATFORM_KEY_SHA256` | 32 字节 Base64url 平台密钥的 SHA-256 十六进制摘要 | 空（禁用平台接口） |
| `NEWCRISP_SELF_SIGNUP_ENABLED` | 是否开放官网租户自助注册 | `true` |
| `NEWCRISP_SIGNUP_VERIFICATION_ENABLED` | 自助注册是否强制校验图形 + 邮箱验证码 | `true` |
| `NEWCRISP_LANDING_DEMO_SITE_ID` | 可选：官网右下角活体体验悬浮窗的站点 ID | 空 |
| `NEWCRISP_MAIL_HOST` / `NEWCRISP_MAIL_PORT` | SMTP 服务器地址与端口（默认启用 SSL） | `smtp.qq.com` / `465` |
| `NEWCRISP_MAIL_USERNAME` | SMTP 发信邮箱 | 空 |
| `NEWCRISP_MAIL_PASSWORD` | SMTP 授权码（QQ 邮箱为 16 位授权码） | 空 |
| `NEWCRISP_JWT_KID` / `NEWCRISP_JWT_PRIVATE_KEY` | RS256 签发密钥 ID 与 PKCS#8 Base64 私钥（本地回环可省） | 自动生成临时密钥 |
| `NEWCRISP_NEXUS_VERIFY_URL` / `NEWCRISP_NEXUS_VERIFY_KEY` | 可选：Nexus 客户会话复核地址与密钥 | 空 |

ECS 的构建、迁移与回滚说明见 [docs/ECS_PREPARATION.md](docs/ECS_PREPARATION.md)。

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
