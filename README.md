# EAM-OPS

基于数据中心场景独立开发的设备资产与智能运维管理平台。Java 后端、Vue 中文管理后台，包含资产、巡检、工单、权限、规则告警与运维看板。所有演示数据均为虚构，不对应真实企业或医院。

验收状态：18 项后端测试通过；完整浏览器闭环和容器运行验收尚未全部完成，具体见 [测试报告](docs/test-report.md)。

## 当前本机运行

项目内已准备便携 Java 17、Maven、MySQL 和 Redis，保存在被 Git 忽略的 `.tools`；数据与日志在 `.runtime`，不安装 Windows 服务。

```powershell
# 窗口一：启动数据库、缓存、后端
pwsh -File scripts/Start-Portable.ps1
# 窗口二：启动前端
pwsh -File scripts/Start-Frontend.ps1
```

打开 http://127.0.0.1:5173 。本机数据库端口 13306，Redis 16379，后端 8080。密码在本机 `.env` 的 `DEMO_PASSWORD`，首次初始化随机生成；不要提交 `.env`。

| 演示账号 | 角色 | 范围 |
| --- | --- | --- |
| admin | 系统管理员 | 全局 |
| asset / asset2 | 资产管理员 | 一部 / 二部 |
| engineer / engineer2 | 运维工程师 | 一部 / 二部 |
| supervisor / supervisor2 | 运维主管 | 一部 / 二部 |
| auditor / auditor2 | 审计人员 | 一部 / 二部日志 |

上述账号共享配置的演示密码，仅在首次启动 `demo` profile 时创建。已有数据库不会重置用户或密码。生产首次启动仅创建 `admin`，密码来自 `BOOTSTRAP_PASSWORD`。

## 新环境部署

依赖 Java 17、Maven 3.9、Node.js 24、MySQL 8、Redis。复制 `.env.example` 为 `.env` 并替换占位值。使用 UTF-8、MySQL InnoDB，业务时区为 Asia/Shanghai。

```powershell
# 有 Docker 的机器，启动全部组件
docker compose --env-file .env -f deploy/compose.yml up -d --build
# 只启动开发用数据库和缓存
docker compose --env-file .env -f deploy/compose.yml up -d mysql redis
# 本机后端 / 前端
pwsh -File scripts/Start-Backend.ps1
pwsh -File scripts/Start-Frontend.ps1
```

完整容器部署访问 http://127.0.0.1:8088 。Compose 默认只绑定本机；需要远程访问时由部署方配置入口、TLS 和网络策略。数据库通过 Flyway 自动迁移。不要将生产数据库改成 demo profile。

便携 Redis Windows 5.0.14.1 仅用于本次本机开发验收；正式部署使用 Compose 中的 Redis 7.4.2。`.tools` 不随源码分发，新环境建议使用 Compose 的 MySQL/Redis 或自行安装服务。便携数据库停止命令和日志位置见 [运维说明](docs/operations.md)。

## 开发与验证

```powershell
pwsh -File scripts/Test-Backend.ps1
# 先创建独立 eam_ops_test 数据库，并授权当前 DB_USER
pwsh -File scripts/Test-Backend.ps1 -Integration
cd frontend
npm ci
npm run build
# 保持后端和前端运行，需要 Chrome 及 demo 环境
npm run test:e2e
```

集成测试仅允许独立 `eam_ops_test` 数据库，添加独立测试数据，不清理生产库。未设置 `EAM_MYSQL_IT=true` 时集成测试会明确跳过。浏览器测试会在 demo 数据库添加名称带验收前缀的数据。

## 工程与功能

- 后端：Spring Boot 3.5.7、Security、JWT、MyBatis-Plus、JdbcTemplate 显式 SQL、Flyway、Redis、JUnit 5。MyBatis-Plus 负责资产实体写入，跨表事务与条件更新使用参数化 SQL。
- 前端：Vue 3、TypeScript、Element Plus、Pinia、ECharts、Vite，版本见 package-lock.json。
- 资产：台账、位置与分类、Excel 全量校验后导入、迁移报废审批、变更履历。
- 巡检：模板、周期计划、快照任务、检查结果、异常关联工单、逾期提醒。
- 工单：分派、接单、处理记录、提交验收、驳回与关闭，版本冲突、事务和设备故障协调。
- 权限：五种内置角色、菜单按钮权限、后端部门过滤、处理人检查、会话撤销。
- 告警：CPU/内存/磁盘/在线规则、活动事件合并、确认与恢复、站内通知。模拟指标仅 demo 可调用。
- 看板：资产快照、工单趋势、状态分布、平均耗时、巡检完成率，按日期及部门统计。

接口文档：[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，[OpenAPI JSON](http://127.0.0.1:8080/v3/api-docs)。登录后将 token 填入 Authorize。

更多：[架构与权限](docs/architecture.md) · [接口与业务规则](docs/api.md) · [演示步骤](docs/demo.md) · [测试报告](docs/test-report.md)

这是独立搭建的个人工程项目，未复用若依代码；第三方库遵循各自许可证。未经实际运行验证的能力不应写成生产上线或性能成果。
