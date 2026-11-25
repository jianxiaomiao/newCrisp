# NewCrisp

独立的多租户在线客服系统。客户从 Nexus Web 进入，客服在独立工作台领取会话并通过 WebSocket 回复。

## 本地工具

需要 JDK 21、Maven、Node.js 20 或更高版本、npm 和运行中的 Docker Desktop 或 Docker Engine。本机已安装 IntelliJ IDEA 时，可以直接使用它内置的 Maven。

## 后端测试

```sh
mvn test
```

JUnit 和 Mockito 验证身份接入、会话权限、幂等重放、旧负责人状态版本和结束前的已读约束。这组测试不需要启动数据库。

## 真实 MySQL 和浏览器验收

```sh
npm ci
npx playwright install chromium --only-shell
npm run test:e2e
```

脚本打包后端，创建临时 MySQL 8.4 容器和 `newcrisp_e2e` 测试库，分配回环数据库端口并生成一次性凭据。Playwright 验证 Flyway V1–V3、平台开通、管理员登录及跨租户隔离。脚本退出时清理本轮容器及其匿名数据卷。

下列命令还会验收真实 Nexus 登录、WebSocket 消息确认丢失重试、并发领取、断线补读、退回及结束。

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

平台 API 使用运维凭据，管理员和客户使用各自的 Host Cookie。正式环境的公开来源使用 HTTPS。一次性跳转凭证有效 60 秒，浏览器以表单正文提交，兑换前复核 Nexus 会话，消费及创建客户会话原子提交。

## 会话与工作台

租户管理员从 `/staff.html` 的「添加客服成员」为本租户现有部门创建客服或主管。普通客服可以领取本部门待领取会话，主管可以在「部门会话」退回会话。客户从 Nexus 的「在线客服」直接发第一条消息。在「已结束」及「历史会话」可查看各自有权限的历史。

WebSocket 入口为 `/ws/customer` 和 `/ws/staff`。浏览器通过 `conversation.start` 和 `message.send` 发送，服务端提交后返回 `message.ack`，使用 `clientMessageId` 去重。页面从 HTTP 查询队列、历史、已读及发送结果，按消息序号补齐缺失消息。位于反向代理后时，代理须启用 WebSocket 升级，并允许超过心跳间隔的连接空闲时间。默认 15 秒探测一次，45 秒未确认后离线。
