# NewCrisp

独立的多租户在线客服系统。首版客户从 Nexus 登录入口进入，客服在独立工作台处理会话，聊天使用 WebSocket。当前骨架包含 Spring Boot 启动、数据库就绪检查和首个租户迁移。

## 本地工具

需要 JDK 21、Maven、Node.js 20 或更高版本、npm 和运行中的 Docker Desktop 或 Docker Engine。本机已安装 IntelliJ IDEA 时，可以直接使用它内置的 Maven。

## 后端测试

```sh
mvn test
```

JUnit 和 Mockito 模拟数据库不可用，验证应用就绪探针返回 503、存活探针仍返回 200。这个测试不需要启动数据库。

## 真实 MySQL 和浏览器验收

```sh
npm ci
npx playwright install chromium --only-shell
npm run test:e2e
```

脚本打包后端，创建临时 MySQL 8.4 容器和 `newcrisp_e2e` 测试库，分配回环数据库端口并生成一次性凭据。Spring Boot 启动时运行 Flyway，Playwright 等待就绪探针，然后用 Chromium 验证服务就绪，并查询容器内的 Flyway V1 记录和 `tenant` 表。脚本退出时清理本轮容器及其匿名数据卷。

如果 `mvn` 不在 `PATH`，可以用 `NEWCRISP_MAVEN` 指定 Maven 可执行文件。浏览器验收的应用端口默认是 `18760`，可通过 `NEWCRISP_E2E_PORT` 修改。数据库结构变更放在 `src/main/resources/db/migration/`，测试环境和验收流程放在 `scripts/`、`playwright.config.mjs` 与 `e2e/`。

在当前 Mac 上，可以在项目终端使用 IntelliJ IDEA 和 HBuilderX 自带的工具，再运行上面的 `npm` 命令：

```sh
export PATH="/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node:/Applications/HBuilderX.app/Contents/HBuilderX/plugins/npm:$PATH"
export NEWCRISP_MAVEN='/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn'
"$NEWCRISP_MAVEN" test
```
