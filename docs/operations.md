# 本地与部署运维

## 启动与停止

本机前后端脚本以前台运行，按 Ctrl+C 停止相应服务。便携 MySQL、Redis 由隐藏后台进程运行，只监听 127.0.0.1，不注册系统服务。

如需停止便携依赖，可在项目根目录使用（密码从 `.env` 加载，避免出现在命令参数中）：

```powershell
Get-Content .env | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') } }
$env:MYSQL_PWD=$env:DB_PASSWORD
& .tools/mysql-8.4.6-winx64/bin/mysqladmin.exe --host=127.0.0.1 --port=13306 --user=root shutdown
$env:MYSQL_PWD=$null
& .tools/redis/redis-cli.exe -p 16379 shutdown
```

Docker 使用 `docker compose --env-file .env -f deploy/compose.yml stop` 停止，`up -d` 恢复。不要在需要保留数据时删除 mysql_data 卷。

## 本地文件

- `.env`：随机演示密码、JWT 密钥、数据库凭据，Git 已忽略。
- `.runtime/mysql-data`：持久化数据库，不能当缓存删除。
- `.runtime/mysql-error.log`、`redis.log`、`backend.log`：本地服务日志。
- `backend/target/surefire-reports`：JUnit XML 与文本结果。
- `frontend/playwright-report`：端到端报告；失败时 `test-results` 保存 trace 和截图。

## 环境检查

健康检查 `GET /actuator/health`；OpenAPI `/v3/api-docs`。后端使用 JSON 结构化日志和 `X-Request-ID`，错误响应带 requestId。

Redis 会话不可用时不会放行受保护接口。数据库或缓存初始化失败应先检查对应服务和凭据；不要改用内存数据库绕过事务测试。

若使用已有本机 MySQL，创建 `eam_ops` 和独立 `eam_ops_test`，将其授权给 DB_USER；`.env` 调整 DB_URL。集成测试脚本可用 `TEST_DB_URL` 指定测试连接，但库名必须为 `eam_ops_test`。

## 版本升级与备份

数据库变更只新增 Flyway 迁移，不修改已上线版本的迁移文件。升级前备份数据库，保留镜像版本；回退应用前确认迁移兼容。Redis 中只有会话和可重建缓存，MySQL 才是业务事实来源。密钥变更会使现有 JWT 失效。

性能优化基线：资产部门/状态/分类索引；工单部门/状态/创建时间、处理人/状态、资产/状态索引；巡检周期唯一键。更大数据规模的性能结论必须在实际数据和测试环境下测量，本项目未宣称吞吐指标。
