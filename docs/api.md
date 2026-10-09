# REST API 与业务操作

前缀 `/api/v1`，除登录外携带 `Authorization: Bearer <token>`。成功返回资源或无内容；失败返回 `{message, requestId}`。401 未登录/会话失效，403 权限不足，404 不存在或不可访问，409 状态/版本/约束冲突，400 参数错误，503 临时失败。

列表统一支持 `page=1&size=20&q=`，size 最大 100；业务状态资源支持 status。部门资源支持 departmentId；工单、任务支持 from/to/overdue；工单、任务、告警、审批支持 assetId。响应 `{items,total,page,size}`，字段采用数据库 snake_case，用户身份对象采用 camelCase。

## 认证和基础配置

- `POST /auth/login`：`{username,password}` → `{token,user}`。
- `GET /auth/me`；`POST /auth/logout`；`POST /auth/password`：`{oldPassword,password}`。
- `GET /options`：返回当前范围的部门、人员、位置、分类、资产、模板选项。
- `GET /users`，`POST /users`：`{username,display_name,password,department_id,role_code}`；`PUT /users/{id}`：`{display_name,department_id,role_code,enabled}`。
- `GET /roles`；`PUT /roles/{code}`：`{permissions:["asset:read",...]}`。
- `GET /departments|categories|locations`；`POST /settings/{kind}`；`PUT /settings/{kind}/{id}`。部门与分类使用 name、enabled，位置增加 department_id、location_type、parent_id。创建时 admin 提供 department_id 作为审计归属。

## 资产

`GET /assets`、`GET /assets/{id}`（含 history）。

```json
{
  "asset_code":"DEV-001","name":"测试服务器","category_id":1,
  "department_id":2,"location_id":2,"owner_id":2,"model":"DEMO","ip_address":"192.0.2.100"
}
```

以上用于 `POST /assets`。`PUT /assets/{id}` 只修改 name、model、ip_address、owner_id，必须增加 version，不能绕过审批修改部门、位置和状态。

- `POST /assets/{id}/lifecycle`：`{version,action:"ISSUE"|"MAINTAIN",reason}`。
- `POST /assets/{id}/approvals`：`{version,action:"MOVE"|"RETIRE",target_location_id,reason}`，报废不需要目标位置。
- `POST /approvals/{id}/review`：`{version,accept:true|false}`。
- `GET /assets/export?template=true|false`：下载 XLSX；导出全部授权资产，上限 10000。
- `POST /assets/import?commit=false|true`：multipart `file`。最多 1000 行、5MB，先校验返回 `{valid,errors:[{row,message}],count,committed}`。commit=true 仍重新验证，任何行错误都不写入。

## 工单

`GET /orders`、`GET /orders/{id}`（含 history）；`POST /orders`：`{title,description,asset_id,priority:1|2|3,source_task_id?}`。来源巡检必须存在异常，同一个任务只生成一个工单。优先级 1/2/3 的截止时间为创建后 4/24/72 小时。

| 动作 POST /orders/{id}/… | 请求字段 | 前置状态 → 结果 |
| --- | --- | --- |
| assign | version, handler_id, detail | 待分派/待接单/处理中 → 待接单 |
| accept | version, detail 可选 | 待接单 → 处理中 |
| record | version, detail | 处理中 → 处理中 |
| submit | version, detail | 处理中 → 待验收 |
| approve | version, detail, recovered | 待验收 → 已关闭 |
| reject | version, detail | 待验收 → 处理中 |

recovered=true 时，关联巡检异常告警恢复；只有设备没有其他活动告警或未关闭工单，才恢复运行。recovered=false 允许关闭该单但保留设备异常状态。

## 巡检、告警与通知

- `GET/POST /templates`，`PUT /templates/{id}`：name、department_id、items 字符串数组，修改带 version。
- `GET/POST /plans`：name、template_id、handler_id、period（DAILY/WEEKLY/MONTHLY）、next_run（YYYY-MM-DD）、asset_ids 数组。
- `POST /plans/{id}/toggle`：version、enabled；`POST /plans/{id}/generate`：立即补齐到期任务。
- `GET /tasks`、`GET /tasks/{id}`；`POST /tasks/{id}/complete`：`{version,results:{"检查项":"NORMAL|ABNORMAL"},description}`。必须恰好覆盖快照的全部检查项，异常必须说明。
- `GET/POST /rules`，`PUT /rules/{id}`：name、department_id、metric、threshold_value、level、enabled，修改带 version。ONLINE 指标 0 触发，1 恢复；资源使用率大于阈值触发。
- `POST /metrics/{assetId}`：`{CPU:95,MEMORY:80,DISK:70,ONLINE:1}`，仅 demo profile 和规则管理权限可用。
- `GET /alerts`；`POST /alerts/{id}/acknowledge|recover`：`{version,detail}`。
- `GET /notifications`；`POST /notifications/{id}/read`，只允许本人。
- `GET /dashboard?from=2026-10-01&to=2026-10-31&departmentId=2`。
- `GET /audit`：按授权部门查询只读审计日志。

无 `/status` 通用更新接口；每个动作必须经过服务层状态机及权限验证。
