export type Field = {
  key: string;
  label?: string;
  type?: string;
  options?: string[];
  source?: string;
  multiple?: boolean;
  optional?: boolean;
};
export type Config = {
  title: string;
  subtitle: string;
  permission: string;
  columns: string[];
  fields?: Field[];
  statuses?: string[];
  create?: string;
  edit?: boolean;
};
const f = (key: string, extra: Partial<Field> = {}): Field => ({
  key,
  ...extra,
});
export const configs: Record<string, Config> = {
  assets: {
    title: "设备资产",
    subtitle: "建立清晰台账，追踪设备的每一次变化",
    permission: "asset:write",
    columns: [
      "asset_code",
      "name",
      "category_id",
      "location_id",
      "owner_id",
      "status",
      "department_id",
    ],
    create: "登记资产",
    edit: true,
    statuses: ["STOCK", "RUNNING", "FAULT", "MAINTENANCE", "RETIRED"],
    fields: [
      f("asset_code"),
      f("name"),
      f("department_id", { source: "departments" }),
      f("category_id", { source: "categories" }),
      f("location_id", { source: "locations" }),
      f("owner_id", { source: "users" }),
      f("model", { optional: true }),
      f("ip_address", { optional: true }),
    ],
  },
  approvals: {
    title: "资产审批",
    subtitle: "迁移与报废申请，审批通过后自动更新资产履历",
    permission: "asset:approve",
    columns: [
      "id",
      "asset_id",
      "action",
      "reason",
      "applicant_id",
      "status",
      "created_at",
    ],
    statuses: ["PENDING", "APPROVED", "REJECTED"],
  },
  orders: {
    title: "故障工单",
    subtitle: "从故障申报到验收关闭，让每个问题得到妥善处理",
    permission: "order:create",
    columns: [
      "order_no",
      "title",
      "asset_id",
      "priority",
      "handler_id",
      "status",
      "due_at",
      "overdue",
    ],
    create: "新建工单",
    statuses: [
      "PENDING",
      "ASSIGNED",
      "IN_PROGRESS",
      "WAITING_REVIEW",
      "CLOSED",
    ],
    fields: [
      f("title"),
      f("asset_id", { source: "assets" }),
      f("priority", { options: ["1", "2", "3"] }),
      f("description", { type: "textarea" }),
    ],
  },
  tasks: {
    title: "巡检任务",
    subtitle: "按计划执行检查，及时发现设备异常",
    permission: "inspection:execute",
    columns: [
      "id",
      "asset_id",
      "handler_id",
      "cycle_date",
      "due_at",
      "status",
      "overdue",
    ],
    statuses: ["PENDING", "COMPLETED"],
  },
  plans: {
    title: "巡检计划",
    subtitle: "日、周、月周期调度，自动生成可追踪的巡检任务",
    permission: "inspection:manage",
    columns: [
      "name",
      "template_id",
      "handler_id",
      "period",
      "next_run",
      "enabled",
    ],
    create: "新建计划",
    fields: [
      f("name"),
      f("template_id", { source: "templates" }),
      f("handler_id", { source: "engineers" }),
      f("period", { options: ["DAILY", "WEEKLY", "MONTHLY"] }),
      f("next_run", { type: "date" }),
      f("asset_ids", { label: "关联设备", source: "assets", multiple: true }),
    ],
  },
  templates: {
    title: "检查模板",
    subtitle: "标准化检查项目，历史任务保留生成时的模板快照",
    permission: "inspection:manage",
    columns: ["name", "department_id", "items", "version"],
    create: "新建模板",
    edit: true,
    fields: [
      f("name"),
      f("department_id", { source: "departments" }),
      f("items", { label: "检查项目（每行一项）", type: "textarea" }),
    ],
  },
  alerts: {
    title: "告警中心",
    subtitle: "确认、跟踪与恢复设备异常，同类活动告警自动合并",
    permission: "alert:write",
    columns: [
      "title",
      "asset_id",
      "level",
      "source",
      "status",
      "occurrences",
      "last_seen_at",
    ],
    statuses: ["OPEN", "ACKNOWLEDGED", "RECOVERED"],
  },
  rules: {
    title: "告警规则",
    subtitle: "配置资源阈值与离线规则，驱动异常发现",
    permission: "alert:manage",
    columns: ["name", "metric", "threshold_value", "level", "enabled"],
    create: "新建规则",
    edit: true,
    fields: [
      f("name"),
      f("department_id", { source: "departments" }),
      f("metric", { options: ["CPU", "MEMORY", "DISK", "ONLINE"] }),
      f("threshold_value", { type: "number" }),
      f("level", { options: ["INFO", "WARNING", "CRITICAL"] }),
      f("enabled", { type: "switch" }),
    ],
  },
  locations: {
    title: "机房与机柜",
    subtitle: "建立设备位置层级，便于资产定位与迁移",
    permission: "asset:write",
    columns: ["name", "location_type", "parent_id", "department_id", "enabled"],
    create: "新增位置",
    edit: true,
    fields: [
      f("name"),
      f("department_id", { source: "departments" }),
      f("location_type", { options: ["ROOM", "RACK"] }),
      f("parent_id", { source: "rooms", optional: true }),
      f("enabled", { type: "switch" }),
    ],
  },
  categories: {
    title: "设备分类",
    subtitle: "统一设备分类字典，支撑台账和统计",
    permission: "asset:write",
    columns: ["name", "enabled"],
    create: "新增分类",
    edit: true,
    fields: [f("name"), f("enabled", { type: "switch" })],
  },
  departments: {
    title: "部门管理",
    subtitle: "维护企业部门及数据访问边界",
    permission: "system:manage",
    columns: ["name", "enabled"],
    create: "新增部门",
    edit: true,
    fields: [f("name"), f("enabled", { type: "switch" })],
  },
  users: {
    title: "用户管理",
    subtitle: "管理账号、角色与所属部门，权限变更后会话自动失效",
    permission: "system:manage",
    columns: [
      "username",
      "display_name",
      "department_id",
      "role_code",
      "enabled",
    ],
    create: "新增用户",
    edit: true,
    fields: [
      f("username"),
      f("display_name"),
      f("password", { label: "初始密码", type: "password" }),
      f("department_id", { source: "departments" }),
      f("role_code", {
        options: ["ADMIN", "ASSET", "ENGINEER", "SUPERVISOR", "AUDITOR"],
      }),
      f("enabled", { type: "switch" }),
    ],
  },
  audit: {
    title: "审计日志",
    subtitle: "只读查看授权范围内的业务操作与历史记录",
    permission: "audit:read",
    columns: [
      "created_at",
      "operator_id",
      "action",
      "target_id",
      "detail",
      "department_id",
    ],
  },
  notifications: {
    title: "通知中心",
    subtitle: "及时关注任务分派、审批结果与逾期提醒",
    permission: "",
    columns: ["title", "content", "read_status", "created_at"],
  },
};
