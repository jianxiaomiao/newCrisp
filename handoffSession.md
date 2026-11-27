# NewCrisp 项目交接与学习跟踪文档（handoffSession.md）

> **维护说明**：本文档是跨对话的唯一事实来源（Source of Truth）。每次开启新对话时，AI 应先完整阅读本文件；每次对话结束或完成重要改动/学习节点后，应及时更新「变更日志」「学习进度勾选」与「待办事项」。

---

## 1. 项目定位与当前状态

- **项目名称**：NewCrisp（对标 Crisp.chat 打造的轻量级多租户 Web 在线客服系统）
- **核心使用场景**：
  1. 作为主产品 **Nexus**（本地仓库 `/Users/xiaomiao/Nexus`）的内置在线客服系统（已登录用户自动携带画像进入）。
  2. 通过单行 `<script>` 悬浮窗脚本嵌入任意外部官网（Shadow DOM 隔离，点击右下角气泡原地弹出，不跳转页面）。
- **明确的范围约束（重要）**：
  - **暂不开发**知识库（Help Center）和 AI 客服，聚焦稳定、好用的轻量级人工客服系统。
  - 附件当前通过 `AttachmentStorage` SPI 存储在 MySQL（后续再平滑替换为本地磁盘 + 定期清理）。
  - 系统已具备完整**多租户（Tenant）+ 部门（Department）+ 角色权限（Admin / Supervisor / Agent）**隔离能力。
- **技术栈**：
  - **后端**：Java 21 + Spring Boot 4.1.1（Spring Web / WebSocket / JDBC Client）+ Flyway + MySQL 8.4 + Jackson 3（`tools.jackson.databind.ObjectMapper`）
  - **前端**：Vue 3 + TypeScript + Vite 8 + Element Plus（**按需手动注册**，见 `web/main.ts`）+ 独立打包的 `widget.ts`（`/widget.js`）
  - **测试**：JUnit 5 + Mockito（后端单测 32/32）+ Playwright + Docker MySQL 8.4（全链路 E2E 13/13）
- **Git 仓库状态**（截至 2026-11-26）：
  - 仓库：`git@github.com:jianxiaomiao/newCrisp.git`（分支 `main`，工作区干净）
  - 最新提交：`1c696a2` — `优化用户端悬浮窗为双界面布局：输入框贴底、左上角返回查看历史会话（最新置顶）`

---

## 2. 已完成里程碑与变更日志

| 日期 | 提交 / 阶段 | 核心内容 |
| :--- | :--- | :--- |
| 2026-11-25 | 基础版本 (`8c95e49`) | 多租户认证、部门与员工管理、WebSocket 聊天、队列领取/转派/退回/结束、附件上传、营业时间、访客聊天与接续码、Element Plus 鼠尾草绿主题。 |
| 2026-11-25 | 三阶段 Crisp 对标升级 (`b1626e6`) | **阶段一（客户画像与聊天体验）**：`V10` 迁移、Nexus `launch-grants` 同步用户资料、客户画像侧边栏与历史会话、Enter/Shift+Enter 发送、乐观发送、粘贴/拖拽传图、图片缩略图与灯箱预览、已读/未读回执、正在输入（typing）、URL 自动链接、表情、Web Audio 提示音。<br>**阶段二（悬浮窗、团队协作与自动化）**：`V11` 迁移、独立 Shadow DOM 悬浮窗 `/widget.js`、`postMessage` 桥接（页面 URL / 未读数）、独立 `conversation_note` 内部备注、会话标签、全库搜索、`/` 快捷回复与变量替换、自动欢迎语、超时自动结束后台任务（`IdleConversationWorker`）、转派交接备注。<br>**阶段三（CSAT、数据看板与架构加固）**：`V12` 迁移、1-5 星满意度评价、管理员运营数据看板（`AnalyticsDashboard.vue`）、`AttachmentStorage` SPI、WebSocket 租户级二级索引 `peersByTenant` 与客户上下线即时通知。 |
| 2026-11-25 | Nexus 联动修改 | 修改 `/Users/xiaomiao/Nexus/nexus-server/.../NexusChatController.java`，查询 `UserMapper` 并在调用 `launch-grants` 时透传 `displayName`、`email`、`emailVerified`、`registeredAt`（已由用户单独提交）。 |
| 2026-11-25 | 缺陷修复 (`f4be1d8`) | 在 `web/main.ts` 补齐按需注册 `ElSelect`、`ElOption`、`ElImageViewer`。 |
| 2026-11-25 | 悬浮窗 UX 优化 (`1c696a2`) | 将悬浮窗（访客 + `embed=1`）改造为双界面：默认对话页输入框贴底，顶部左上角 `<` 切入「全部/历史会话」列表（最新会话置顶），访客接续码收纳至顶部标题栏右侧。 |
| 2026-11-26 | 交接文档建立 | 创建 `handoffSession.md`，规划源码级内部/外部链路学习路线。 |
| 2026-11-27 | 源码全景学习完成 | 完成第 4 节全部 8 个模块的源码精读与链路梳理（数据模型、Nexus 接入、悬浮窗与接续、JWT 轮换与 RBAC、WS 与 Outbox 补偿、状态机与调度、协作/附件/统计、前端工程与双界面 UI）。 |
| 2026-11-27 | 对外 SaaS 官网 + 平台管理台 | 新增 SaaS 官网首页（`/` → `LandingPage.vue`）与公开租户自助注册（`PublicOnboardingController`，支持保留字拦截、IP 限流、开关控制 `NEWCRISP_SELF_SIGNUP_ENABLED`，注册后自动登录跳转 `/staff.html`，支持 `NEWCRISP_LANDING_DEMO_SITE_ID` 挂载活体悬浮窗）；新增平台超管控制台（`/platform.html` → `PlatformPage.vue` + `PlatformStore` + 扩展 `PlatformController`），支持 Platform Key 签发 `HttpOnly` Cookie 会话（兼容原 Bearer Key）、全平台概览 KPI、租户搜索筛选、新建租户、启用/停用（同步吊销员工会话）、配置/轮换 Nexus 集成与重置租户管理员密码。单测 35/35 通过。 |

---

## 3. 代码地图（Source Code Map）

### 3.1 后端：认证、多租户与接入（`src/main/java/com/newcrisp/identity/`）
- **配置与基础工具**：`IdentityConfiguration.java`、`IdentityHttp.java`、`IdentityError.java`、`ChatIdentity.java`
- **令牌与会话轮换**：`AccessJwt.java`、`IdentityTokens.java`、`WebTokens.java`、`TokenStore.java`、`WebSessionController.java`
- **平台与租户后台**：`PlatformController.java`、`PlatformStore.java`、`PublicOnboardingController.java`、`TenantManagementController.java`、`TenantManagementService.java`、`TenantManagementStore.java`、`SupportSettingsController.java`
- **员工认证**：`StaffController.java`、`StaffService.java`、`StaffStore.java`
- **Nexus 外部接入链路**：`NexusLaunchService.java`、`NexusSessionVerifier.java`、`NexusStore.java`、`CustomerController.java`
- **访客悬浮窗接入链路**：`GuestChatController.java`、`GuestChatService.java`

### 3.2 后端：实时聊天、协作与运营（`src/main/java/com/newcrisp/chat/`）
- **核心聊天与存储**：`ChatController.java`、`ChatService.java`、`ChatStore.java`、`ChatModels.java`、`CustomerUnread.java`
- **实时通道与在线态**：`ChatSocketConfiguration.java`、`ChatWebSocket.java`、`ChatPresence.java`
- **后台调度与规则**：`QueueAssignmentWorker.java`（自动分配）、`IdleConversationWorker.java`（超时自动关闭）、`ServiceHours.java`（营业时间）、`WorkbenchControls.java`（快捷回复/接单状态）
- **协作、附件与统计**：`CollaborationController.java`（内部备注/标签/搜索/CSAT）、`AttachmentStorage.java` + `ChatFiles.java`、`AnalyticsController.java`

### 3.3 数据库迁移（`src/main/resources/db/migration/`）
- `V1__create_tenant.sql` ~ `V5__manage_tenant_staff.sql`：租户、身份、会话消息表、会话轮换、部门与员工管理
- `V6__automatic_chat_assignment.sql` ~ `V9__guest_chat.sql`：自动分配、运营规则/快捷回复、附件表、访客站点与访客映射
- `V10__customer_profile_and_context.sql`：客户画像字段、上下文 `last_page_url`
- `V11__collaboration_and_automation.sql`：`conversation_note`、`tag`、`conversation_tag`、欢迎语与自动关闭配置
- `V12__csat_analytics_and_storage.sql`：`conversation_rating`、`first_staff_reply_at`、存储扩展字段

### 3.4 前端（`web/`）
- **入口与基础设施**：`main.ts`（页面路由分发 + Element Plus 按需注册）、`api.ts`、`web-session.ts`、`chat-utils.ts`、`confirmation.ts`、`staff-notifications.ts`、`identity.css`
- **外部悬浮窗脚本**：`widget.ts`（独立构建为 `target/classes/static/widget.js`，Shadow DOM 注入 iframe）
- **业务页面与组件**：
  - `LandingPage.vue`：对外 SaaS 官网首页（`/`、`index.html`，含租户自助注册与可选活体悬浮窗）
  - `PlatformPage.vue`：平台超管控制台（`/platform.html`，Platform Key 登录、跨租户全生命周期管理）
  - `IdentityPage.vue`：客服/管理员工作台 + Nexus 已登录客户端（支持 `?embed=1`）
  - `GuestPage.vue`：外部官网匿名访客端（`/embed/{siteId}`）
  - `ChatPanel.vue`：核心双端复用聊天面板（工作台 + 客户端 + 紧凑双界面悬浮窗）
  - `AttachmentMessage.vue`：附件下载、图片自动缩略图与灯箱预览
  - `TenantManagement.vue`、`SupportAdmin.vue`、`AnalyticsDashboard.vue`、`BrandMark.vue`

### 3.5 Nexus 侧联调入口（`/Users/xiaomiao/Nexus`）
- 后端：`/Users/xiaomiao/Nexus/nexus-server/src/main/java/com/nexus/supportchat/NexusChatController.java`
- 前端：`/Users/xiaomiao/Nexus/nexus-console`（通过 `VITE_NEXUS_CHAT_ENABLED=true` 启用在线客服入口）

---

## 4. 源码级系统熟悉路线图（学习进度跟踪）

> **教学方式约定**：新对话应以「**先画调用链路图 → 精读关键源码文件（附带行号） → 拆解设计意图与边界条件 → 提问/小测确认掌握 → 勾选本表进度**」的节奏推进，不要一次灌输过多内容，每次专注 1 个模块。

- [x] **模块 1：全局骨架与多租户数据模型**
  - 入口 `NewCrispApplication.java`、`application.yml`、Flyway `V1`–`V12` 核心表关系（`tenant` / `staff` / `department` / `customer` / `conversation` / `conversation_message` / `conversation_note`）。
- [x] **模块 2：外部链路 A —— Nexus 已登录用户单点接入**
  - `NexusChatController`（Nexus 后端） → `POST /integrations/nexus/launch-grants` → `NexusLaunchService` → `/launch/nexus` → `WebSessionController` → `IdentityPage.vue`（含客户画像同步与 `embed=1` 模式）。
- [x] **模块 3：外部链路 B —— 外部官网悬浮窗与匿名访客**
  - `web/widget.ts`（Shadow DOM、气泡、未读红点、`postMessage` 双向通信） → `/embed/{siteId}` → `GuestPage.vue` → `GuestChatController` / `GuestChatService` → 访客接续码生成与 Nexus 账号合并（`guest-link`）。
- [x] **模块 4：内部链路 A —— 认证、Token 轮换与 RBAC 权限控制**
  - `StaffController` / `StaffService`、`AccessJwt`、Refresh Session 轮换（`TokenStore`）、`X-NewCrisp-Actor` & `X-NewCrisp-Staff-Access`（`accessVersion` 防权限陈旧）、`TenantManagementController`。
- [x] **模块 5：内部链路 B —— 实时消息与 WebSocket 全链路**
  - `POST /ws-ticket` 一次性票据 → `ChatWebSocket` 鉴权与 `peersByTenant` 索引 → `conversation.start` / `message.send` / `message.ack` / `message.created` / `typing` → 客户端 `ChatPanel.vue` 乐观发送、幂等 `clientMessageId`、`seq` 连续性与断线 `sync()` 补偿、已读水位 `read-position`。
- [x] **模块 6：内部链路 C —— 会话状态机、并发控制与后台调度**
  - `WAITING` / `ACTIVE` / `CLOSED` 状态流转与 `state_version` 乐观锁 → `claim` / `return` / `transfer` / `close`（含 `observedCustomerSeq` 防漏读） → `QueueAssignmentWorker` 自动分配 → `IdleConversationWorker` 超时自动结束 → `ServiceHours` 营业时间。
- [x] **模块 7：内部链路 D —— 团队协作、附件存储与数据运营**
  - 独立 `conversation_note` 为什么不能混进 `conversation_message` → 标签与全库搜索（`CollaborationController`） → 快捷回复与 `/` 模板变量 → `AttachmentStorage` SPI（MySQL → 本地磁盘演进点） → CSAT 评价与 `AnalyticsController` / `AnalyticsDashboard.vue`。
- [x] **模块 8：前端工程与 UI 交互全景**
  - `vite.config.ts` 多入口与 `widget.js` 打包、`main.ts` 按需注册策略、`ChatPanel.vue` 桌面端 vs 悬浮窗双界面响应式机制、E2E 测试架构（`e2e/`）。

---

## 5. 关键架构约束与踩坑备忘

1. **Jackson 3**：Spring Boot 4.1.1 使用 `tools.jackson.databind.ObjectMapper`（而非 `com.fasterxml.jackson`），且 `application.yml` 开启了 `fail-on-unknown-properties: true`。
2. **Element Plus 手动按需注册**：`web/main.ts` 未使用全局导入；凡是在 `.vue` 中新增 Element Plus 组件，必须在 `web/main.ts` 显式 import 并注册。
3. **`conversation_message.seq` 连续性不变量**：每段会话的 `seq` 严格递增连续，用于客户端断线补拉与已读水位判断，因此**内部备注必须独立存储在 `conversation_note` 表**，绝不能占用 `seq`。
4. **乐观消息渲染隔离**：`ChatPanel.vue` 中未 ACK 的乐观消息渲染在 `<ol aria-label="消息">` 之外的 `.optimistic-queue` 中，避免被 E2E 或已读逻辑误判为已落库消息。
5. **悬浮窗双界面兼容**：`GuestPage.vue` 将「显示访客接续码」放在顶部标题栏右侧，保证默认进入对话页时既不占底部高度，又能直接通过 `e2e/nexus-login.spec.mjs` 的访客接续测试。

---

## 6. 本地开发环境与常用命令

- **本机工具链路径（macOS）**：
  - Node / npm：`/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node` 与 `.../plugins/npm`
  - Maven：`/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`
  - GitHub 推送私钥：`~/.ssh/nexus_github_ed25519`
- **常用命令**：
  ```bash
  # 设置 PATH
  export PATH="/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node:/Applications/HBuilderX.app/Contents/HBuilderX/plugins/npm:/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin:$PATH"

  # 前端类型检查与构建
  npm run build

  # 后端单测 / 打包
  mvn test
  mvn -q package -DskipTests

  # 全量真实 MySQL 8.4 + Nexus Playwright E2E（需先启动 Docker Desktop）
  NEWCRISP_E2E_NEXUS_ROOT=/Users/xiaomiao/Nexus npm run test:e2e

  # 推送 GitHub
  GIT_SSH_COMMAND="ssh -i ~/.ssh/nexus_github_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes" git push origin main
  ```

---

## 7. 待办与后续演进（Backlog）

- [ ] 启动 Docker Desktop 后补跑一次完整 `npm run test:e2e`，验证悬浮窗双界面改动（`1c696a2`）全绿。
- [ ] 为悬浮窗双界面、内部备注、标签、CSAT、数据看板补充专项 Playwright E2E 用例。
- [ ] 未来按需将 `AttachmentStorage` 从 MySQL 实现切换为本地磁盘存储 + 定时过期清理。
- [x] 持续跟随第 4 节路线图完成全部 8 个模块的源码精读，直到完全掌握系统。
