# NewCrisp

独立的多租户在线客服系统。客户从 Nexus Web 进入，客服在独立工作台领取会话并通过 WebSocket 回复。

## 本地工具

需要 JDK 21、Maven、Node.js 20 或更高版本、npm 和运行中的 Docker Desktop 或 Docker Engine。本机已安装 IntelliJ IDEA 时，可以直接使用它内置的 Maven。

## 后端测试

```sh
mvn test
```

JUnit 和 Mockito 验证身份接入、JWT 受众与过期、刷新轮换及重放撤销、单次票据，以及会话权限、幂等和结束前的已读约束。这组测试不需要启动数据库。

## 真实 MySQL 和浏览器验收

```sh
npm ci
npx playwright install chromium --only-shell
npm run test:e2e
```

脚本打包后端，创建临时 MySQL 8.4 容器和 `newcrisp_e2e` 测试库，分配回环数据库端口并生成一次性凭据。Playwright 验证 Flyway V1–V4、平台开通、租户隔离、多标签刷新、令牌重放、票据消费和退出断连。脚本退出时清理本轮容器及其匿名数据卷。

下列命令还会验收真实 Nexus 登录、控制台未读角标与读回执、账号切换、WebSocket 消息确认丢失重试、并发领取、断线补读、退回及结束。

```sh
NEWCRISP_E2E_NEXUS_ROOT=/Users/xiaomiao/Nexus npm run test:e2e
```

完整验收在临时目录用 Nexus 当前源码和依赖锁构建 Web 和后端。Nexus 使用容器中单独的 `nexus_test` 库和临时账号，默认回环端口为 `18761`、`18762`。可用 `NEWCRISP_E2E_NEXUS_API_PORT`、`NEWCRISP_E2E_NEXUS_WEB_PORT` 指定其他端口。

如果 `mvn` 不在 `PATH`，可以用 `NEWCRISP_MAVEN` 指定 Maven 可执行文件。浏览器验收的应用端口默认是 `18760`，可通过 `NEWCRISP_E2E_PORT` 修改。数据库结构变更放在 `src/main/resources/db/migration/`，测试环境和验收流程放在 `scripts/`、`playwright.config.mjs` 与 `e2e/`。

在当前 Mac 上，可以在项目终端使用 IntelliJ IDEA 和 HBuilderX 自带的工具，再运行上面的 `npm` 命令：

```sh
export PATH="/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node:/Applications/HBuilderX.app/Contents/HBuilderX/plugins/npm:$PATH"
export NEWCRISP_MAVEN='/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn'
"$NEWCRISP_MAVEN" test
```

## 开通与接入

1. 启动 NewCrisp 时配置 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`NEWCRISP_PUBLIC_ORIGIN`。平台操作凭据使用 32 个随机字节的无填充 Base64url 表示。`NEWCRISP_PLATFORM_KEY_SHA256` 配置该字符串的 SHA-256 十六进制摘要，平台操作时通过 `Authorization: Bearer` 提交原凭据。
2. 平台调用 `POST /api/v1/platform/tenants`，提交 `code`、`name`、`adminEmail` 和 `adminPassword`。初始密码为 12–72 个 UTF-8 字节。租户、默认「客户支持」部门和首位管理员在同一事务创建。管理员从 `/staff.html` 输入租户代码、邮箱和密码。
3. 平台调用 `POST /api/v1/platform/tenants/{tenantCode}/nexus-integrations`，提交 `nexusOrigin`，值为实际 Nexus Web 来源（协议、主机、可选端口）。响应中的 `credential` 只在创建时返回。Nexus 后端用 `NEXUS_CHAT_ORIGIN` 指向 NewCrisp 公共来源，用 `NEXUS_CHAT_CREDENTIAL` 保存该凭据。Nexus Web 构建时设置 `VITE_NEXUS_CHAT_ENABLED=true` 显示「在线客服」入口。
4. 另生成一个独立的 32 字节 Base64url 复核凭据。NewCrisp 使用 `NEWCRISP_NEXUS_VERIFY_KEY` 保存原凭据，`NEWCRISP_NEXUS_VERIFY_URL` 指向 Nexus 的 `/api/internal/support/chat/session-status`。Nexus 使用 `NEXUS_CHAT_VERIFY_KEY_SHA256` 保存该凭据的 SHA-256 十六进制摘要。当前实例接入一个 Nexus 身份源，客户会话的 Nexus 校验结果最多缓存 60 秒。

平台 API 使用运维凭据，客户和员工使用各自的 JWT 受众和 Refresh Cookie。正式环境的公开来源使用 HTTPS。Nexus 跳转凭证有效 60 秒，浏览器以表单正文提交。凭证消费与短期开通凭据原子写入，NewCrisp 页面在同源 Web Lock 内开通正式会话。

Nexus Web 的「在线客服」角标通过 Nexus 后端查询当前登录用户的未读回复总数。NewCrisp 从接入凭据确定租户和入口。控制台返回、聚焦或进入前台时刷新，前台停留时每 15 秒补查。已读状态以客服页面保存的读回执为准。

## 会话与工作台

租户管理员从 `/staff.html` 的「添加客服成员」为本租户现有部门创建客服或主管。普通客服可以领取本部门待领取会话，主管可以在「部门会话」退回会话。客户从 Nexus 的「在线客服」直接发第一条消息。在「已结束」及「历史会话」可查看各自有权限的历史。

WebSocket 入口为 `/ws/customer` 和 `/ws/staff`。页面先用 Bearer Token 调用对应的 `POST /api/v1/{side}/ws-ticket`，再在连接首帧提交 `auth` 和 `ticket`。服务端返回 `ready` 后才允许 `conversation.start` 和 `message.send`。消息提交后返回 `message.ack`，使用 `clientMessageId` 去重。重连领取新票据，页面通过 HTTP 按消息序号补齐缺失消息。反向代理须启用 WebSocket 升级，并允许超过心跳间隔的连接空闲时间。默认 15 秒探测一次，45 秒未确认后离线。

## JWT 与 Refresh Token

- Access JWT 默认 5 分钟有效，使用 RS256，Web 只在内存保存。客户与员工的受众独立，服务端每次鉴权检查会话撤销状态，业务操作继续检查租户、角色和会话权限。
- Refresh Token 为随机不透明值，通过 `HttpOnly`、`Secure`、`SameSite=Lax` Cookie 提交到 `POST /api/v1/{side}/session/refresh`。同时校验 Origin 和配套 CSRF 值。MySQL 只保存摘要、所属会话、到期及消费时间。会话最长 8 小时。旧令牌重放会撤销同一个 `sid` 下的全部凭据并关闭已有连接。
- 两个标签页通过同源 Web Lock 串行刷新。浏览器只共享会话标识和刷新状态。请求结果不确定时，要求重新认证。Nexus 客户刷新前须复核关联登录会话。
- WebSocket 票据只消费一次，最长 30 秒有效，首帧须在连接后 5 秒内提交。连接权限不超过对应 Access JWT 的有效期。登出立即撤销会话并关闭当前实例上的对应连接。

部署时设置 `NEWCRISP_JWT_KID` 和 `NEWCRISP_JWT_PRIVATE_KEY`。私钥格式为至少 2048 位 RSA、PKCS#8 DER 的单行 Base64。轮换时用 `NEWCRISP_JWT_PREVIOUS_KID` 和 `NEWCRISP_JWT_PREVIOUS_PUBLIC_KEY` 保留上一把验签公钥，公钥格式为 X.509 DER 的单行 Base64，待旧 Access JWT 到期后移除。回环开发环境可以省略私钥，进程启动时生成临时密钥。升级前的浏览器 Cookie 会话需重新登录或从 Nexus 重新进入。
