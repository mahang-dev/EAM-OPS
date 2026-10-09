<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, get, post } from "../api";
import { useAuth } from "../store";
import { configs, type Field } from "../resources";
import { labels, states, tone } from "../labels";
const route = useRoute(),
  router = useRouter(),
  auth = useAuth(),
  resource = String(route.params.resource),
  config = configs[resource];
const rows = ref<any[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(20),
  q = ref(""),
  status = ref(String(route.query.status || "")),
  busy = ref(false),
  error = ref(false),
  options = ref<Record<string, any[]>>({}),
  dialog = ref(false),
  dialogTitle = ref(""),
  fields = ref<Field[]>([]),
  form = ref<Record<string, any>>({}),
  saving = ref(false),
  endpoint = ref(""),
  method = ref("post"),
  detail = ref<any>(),
  drawer = ref(false),
  checking = ref(false),
  importDialog = ref(false),
  file = ref<File>(),
  importResult = ref<any>(),
  metrics = ref(false);
const department = ref<number>(Number(route.query.departmentId) || 0);
const own = (r: any) =>
  auth.user?.role === "ADMIN" || r.handler_id === auth.user?.id;
function source(name: string) {
  let list =
    name === "engineers"
      ? (options.value.users || []).filter((x) => x.role_code === "ENGINEER")
      : name === "rooms"
        ? (options.value.locations || []).filter(
            (x) => x.location_type === "ROOM",
          )
        : options.value[name] || [];
  const dept =
    form.value.department_id ||
    options.value.assets?.find((x) => x.id === form.value.asset_id)
      ?.department_id ||
    options.value.templates?.find((x) => x.id === form.value.template_id)
      ?.department_id;
  if (dept && name !== "departments")
    list = list.filter((x) => !x.department_id || x.department_id === dept);
  return list;
}
function display(key: string, value: any): string {
  if (value === null || value === undefined || value === "") return "—";
  const lookup: Record<string, string> = {
    department_id: "departments",
    asset_id: "assets",
    category_id: "categories",
    location_id: "locations",
    target_location_id: "locations",
    parent_id: "locations",
    template_id: "templates",
    owner_id: "users",
    handler_id: "users",
    operator_id: "users",
    created_by: "users",
    applicant_id: "users",
    reviewer_id: "users",
  };
  const record = options.value[lookup[key] || ""]?.find((x) => x.id === value);
  if (record) return record.display_name || record.name;
  if (["enabled", "overdue", "read_status", "abnormal"].includes(key))
    return value ? "是" : "否";
  if (key === "priority")
    return (
      { "1": "紧急", "2": "普通", "3": "低" }[String(value)] || String(value)
    );
  if (typeof value === "object") return JSON.stringify(value);
  return states[String(value)] || String(value);
}
async function load() {
  if (!config) return;
  busy.value = true;
  error.value = false;
  try {
    const data = await get("/" + resource, {
      page: page.value,
      size: size.value,
      q: q.value,
      status: status.value,
      departmentId: department.value || undefined,
      from: route.query.from,
      to: route.query.to,
      overdue: route.query.overdue,
      assetId: route.query.assetId,
    });
    rows.value = data.items;
    total.value = data.total;
  } catch {
    error.value = true;
  } finally {
    busy.value = false;
  }
}
async function loadOptions() {
  if (auth.user?.role !== "AUDITOR")
    try {
      options.value = await get("/options");
    } catch {}
}
onMounted(async () => {
  if (!auth.user) await auth.load();
  await loadOptions();
  await load();
});
watch(department, () => {
  page.value = 1;
  load();
});
function open(
  title: string,
  path: string,
  fs: Field[],
  initial: Record<string, any> = {},
  verb = "post",
) {
  dialogTitle.value = title;
  endpoint.value = path;
  fields.value = fs;
  form.value = {
    department_id: auth.user?.departmentId,
    enabled: true,
    priority: "2",
    ...initial,
  };
  method.value = verb;
  dialog.value = true;
}
function edit(row?: any) {
  let fs = [...(config?.fields || [])];
  const initial = { ...row };
  if (row && resource === "assets")
    fs = fs.filter((f) =>
      ["name", "model", "ip_address", "owner_id"].includes(f.key),
    );
  if (row && resource === "users")
    fs = fs.filter((f) => !["username", "password"].includes(f.key));
  if (!row) fs = fs.filter((f) => f.key !== "enabled");
  if (resource === "templates" && row)
    initial.items = JSON.parse(row.items).join("\n");
  if (auth.user?.role !== "ADMIN")
    fs = fs.filter((f) => f.key !== "department_id");
  const prefix = ["categories", "departments", "locations"].includes(resource)
    ? "/settings/"
    : "/";
  open(
    row ? "编辑" + config.title : config.create!,
    prefix + resource + (row ? "/" + row.id : ""),
    fs,
    initial,
    row ? "put" : "post",
  );
}
async function save() {
  for (const f of fields.value) {
    const v = form.value[f.key];
    if (
      !f.optional &&
      f.type !== "switch" &&
      (v === undefined ||
        v === null ||
        v === "" ||
        (Array.isArray(v) && !v.length))
    ) {
      ElMessage.warning("请填写" + (f.label || labels[f.key] || f.key));
      return;
    }
  }
  saving.value = true;
  try {
    const payload = { ...form.value };
    if (resource === "templates" && typeof payload.items === "string")
      payload.items = payload.items
        .split("\n")
        .map((x: string) => x.trim())
        .filter(Boolean);
    if (endpoint.value.endsWith("/complete")) {
      payload.results = {};
      for (const f of fields.value.filter((x) => x.key.startsWith("check:")))
        payload.results[f.label!] = payload[f.key];
    }
    await api.request({
      url: endpoint.value,
      method: method.value,
      data: payload,
    });
    ElMessage.success("操作成功");
    dialog.value = false;
    await loadOptions();
    await load();
  } catch {
  } finally {
    saving.value = false;
  }
}
async function show(row: any) {
  if (resource === "users") {
    edit(row);
    return;
  }
  try {
    detail.value = await get("/" + resource + "/" + row.id);
    drawer.value = true;
  } catch {}
}
async function action(row: any, act: string) {
  if (act === "generate" || act === "read" || act === "toggle") {
    try {
      if (act !== "read")
        await ElMessageBox.confirm(
          act === "generate"
            ? "立即生成所有到期周期的任务？重复执行不会产生重复任务。"
            : "确认更改计划启用状态？",
          "确认操作",
        );
      await post(
        "/" + resource + "/" + row.id + "/" + act,
        act === "toggle" ? { version: row.version, enabled: !row.enabled } : {},
      );
      ElMessage.success("操作成功");
      await load();
    } catch {}
    return;
  }
  if (act === "complete") {
    const items = JSON.parse(row.snapshot);
    open(
      "执行巡检",
      "/tasks/" + row.id + "/complete",
      [
        ...items.map((label: string, i: number) => ({
          key: "check:" + i,
          label,
          options: ["NORMAL", "ABNORMAL"],
        })),
        {
          key: "description",
          label: "结果说明（异常时必填）",
          type: "textarea",
          optional: true,
        },
      ],
      { version: row.version },
    );
    return;
  }
  if (act === "workorder") {
    router.push({
      path: "/orders",
      query: { source_task_id: row.id, asset_id: row.asset_id },
    });
    return;
  }
  const base = {
    version: row.version,
    department_id: row.department_id,
    asset_id: row.asset_id,
  };
  if (resource === "orders") {
    const title: Record<string, string> = {
      assign: "分派 / 转派工单",
      accept: "接单",
      record: "记录处理过程",
      submit: "提交处理结果",
      approve: "验收通过并关闭",
      reject: "驳回返工",
    };
    const fs: Field[] =
      act === "assign"
        ? [
            { key: "handler_id", source: "engineers" },
            { key: "detail", label: "分派原因", type: "textarea" },
          ]
        : [
            {
              key: "detail",
              label: act === "accept" ? "接单备注" : "处理说明",
              type: "textarea",
              optional: act === "accept",
            },
          ];
    if (act === "approve")
      fs.push({
        key: "recovered",
        label: "确认设备故障已恢复",
        type: "switch",
      });
    open(title[act]!, `/orders/${row.id}/${act}`, fs, {
      ...base,
      recovered: false,
    });
    return;
  }
  if (resource === "assets") {
    if (act === "MOVE" || act === "RETIRE") {
      open(
        act === "MOVE" ? "申请迁移" : "申请报废",
        `/assets/${row.id}/approvals`,
        [
          ...(act === "MOVE"
            ? [{ key: "target_location_id", source: "locations" }]
            : []),
          { key: "reason", type: "textarea" },
        ],
        { ...base, action: act },
      );
      return;
    }
    open(
      act === "ISSUE" ? "领用设备" : "进入维修",
      `/assets/${row.id}/lifecycle`,
      [{ key: "reason", type: "textarea" }],
      { ...base, action: act },
    );
    return;
  }
  if (resource === "approvals") {
    open(
      act === "approve" ? "审批通过" : "审批驳回",
      `/approvals/${row.id}/review`,
      [],
      { ...base, accept: act === "approve" },
    );
    return;
  }
  if (resource === "alerts")
    open(
      act === "acknowledge" ? "确认告警" : "登记故障恢复",
      `/alerts/${row.id}/${act}`,
      [{ key: "detail", label: "处理说明", type: "textarea" }],
      base,
    );
}
async function download(template = false) {
  try {
    const r = await api.get("/assets/export", {
      params: { template },
      responseType: "blob",
    });
    const url = URL.createObjectURL(r.data);
    const a = document.createElement("a");
    a.href = url;
    a.download = template ? "资产导入模板.xlsx" : "资产台账.xlsx";
    a.click();
    URL.revokeObjectURL(url);
  } catch {}
}
async function importFile(commit = false) {
  if (!file.value) return;
  checking.value = true;
  try {
    const fd = new FormData();
    fd.append("file", file.value);
    importResult.value = (
      await api.post("/assets/import", fd, { params: { commit } })
    ).data;
    if (importResult.value.committed) {
      ElMessage.success("导入完成");
      await load();
      await loadOptions();
    }
  } catch {
  } finally {
    checking.value = false;
  }
}
function metricDialog() {
  metrics.value = true;
  open(
    "模拟设备指标上报",
    "/metrics/0",
    [
      { key: "asset_id", source: "assets" },
      { key: "CPU", label: "CPU 使用率", type: "number" },
      { key: "MEMORY", label: "内存使用率", type: "number" },
      { key: "DISK", label: "磁盘使用率", type: "number" },
      {
        key: "ONLINE",
        label: "在线状态（0 离线 / 1 在线）",
        options: ["0", "1"],
      },
    ],
    { CPU: 30, MEMORY: 40, DISK: 50, ONLINE: "1" },
  );
}
async function submit() {
  if (endpoint.value.startsWith("/metrics/")) {
    if (!form.value.asset_id) {
      ElMessage.warning("请选择设备");
      return;
    }
    saving.value = true;
    try {
      await post("/metrics/" + form.value.asset_id, {
        CPU: Number(form.value.CPU),
        MEMORY: Number(form.value.MEMORY),
        DISK: Number(form.value.DISK),
        ONLINE: Number(form.value.ONLINE),
      });
      dialog.value = false;
      ElMessage.success("指标已上报");
      await load();
    } catch {
    } finally {
      saving.value = false;
    }
  } else await save();
}
onMounted(() => {
  if (resource === "orders" && route.query.source_task_id) {
    edit();
    form.value.source_task_id = Number(route.query.source_task_id);
    form.value.asset_id = Number(route.query.asset_id);
    form.value.title = "巡检异常处理";
    form.value.description = "来自巡检任务 #" + route.query.source_task_id;
  }
});
const visibleDetail = computed(() =>
  Object.entries(detail.value || {}).filter(
    ([k]) => !["history", "records", "active_key", "dedup_key"].includes(k),
  ),
);
</script>
<template>
  <template v-if="config"
    ><div class="page-heading">
      <div>
        <div class="eyebrow">ENTERPRISE OPERATIONS</div>
        <h1>{{ config.title }}</h1>
        <p>{{ config.subtitle }}</p>
      </div>
      <div class="heading-tools">
        <el-button v-if="resource === 'assets'" @click="download(false)"
          >导出台账</el-button
        ><el-button
          v-if="resource === 'assets' && auth.can('asset:write')"
          @click="
            importDialog = true;
            importResult = undefined;
          "
          >导入 Excel</el-button
        ><el-button
          v-if="resource === 'alerts' && auth.demo && auth.can('alert:manage')"
          @click="metricDialog"
          >模拟指标</el-button
        ><el-button
          v-if="config.create && auth.can(config.permission)"
          type="primary"
          @click="edit()"
          >＋ {{ config.create }}</el-button
        >
      </div>
    </div>
    <section class="panel list-panel">
      <div class="filter-bar">
        <el-input
          v-model="q"
          clearable
          placeholder="搜索关键词"
          style="width: 250px"
          @keyup.enter="
            page = 1;
            load();
          "
          @clear="
            page = 1;
            load();
          "
        /><el-select
          v-if="config.statuses"
          v-model="status"
          clearable
          placeholder="全部状态"
          style="width: 145px"
          @change="
            page = 1;
            load();
          "
          ><el-option
            v-for="s in config.statuses"
            :key="s"
            :label="states[s]"
            :value="s" /></el-select
        ><el-select
          v-if="auth.user?.role === 'ADMIN' && options.departments"
          v-model="department"
          placeholder="全部部门"
          style="width: 160px"
          ><el-option :value="0" label="全部部门" /><el-option
            v-for="d in options.departments"
            :key="d.id"
            :value="d.id"
            :label="d.name" /></el-select
        ><el-button
          type="primary"
          plain
          @click="
            page = 1;
            load();
          "
          >查询</el-button
        ><el-button @click="load">刷新</el-button
        ><span class="result-count">共 {{ total }} 条记录</span>
      </div>
      <el-alert
        v-if="error"
        title="数据加载失败，请检查权限或点击刷新重试"
        type="error"
        :closable="false"
      />
      <el-table
        v-loading="busy"
        :data="rows"
        empty-text="暂无记录，可使用右上角按钮创建"
        @row-dblclick="show"
        stripe
        ><el-table-column
          v-for="column in config.columns"
          :key="column"
          :label="labels[column] || column"
          :min-width="
            [
              'title',
              'name',
              'detail',
              'description',
              'content',
              'due_at',
              'created_at',
              'last_seen_at',
            ].includes(column)
              ? 190
              : column === 'order_no'
                ? 220
                : 120
          "
          show-overflow-tooltip
          ><template #default="{ row }"
            ><el-tag
              v-if="['status', 'level'].includes(column)"
              :type="tone(row[column])"
              effect="light"
              size="small"
              >{{ display(column, row[column]) }}</el-tag
            ><span
              v-else-if="column === 'overdue'"
              :class="row[column] ? 'text-orange' : ''"
              >{{ display(column, row[column]) }}</span
            ><span v-else>{{ display(column, row[column]) }}</span></template
          ></el-table-column
        >
        <el-table-column
          label="操作"
          fixed="right"
          :width="
            resource === 'orders' || resource === 'assets'
              ? 260
              : resource === 'tasks'
                ? 230
                : 170
          "
          ><template #default="{ row }"
            ><div class="row-actions">
              <el-button
                v-if="resource !== 'users'"
                link
                type="primary"
                @click="show(row)"
                >详情</el-button
              ><el-button
                v-if="
                  config.edit &&
                  auth.can(config.permission) &&
                  row.status !== 'RETIRED'
                "
                link
                type="primary"
                @click="edit(row)"
                >编辑</el-button
              >
              <template
                v-if="
                  resource === 'assets' &&
                  auth.can('asset:write') &&
                  row.status !== 'RETIRED'
                "
                ><el-button
                  v-if="row.status === 'STOCK'"
                  link
                  type="primary"
                  @click="action(row, 'ISSUE')"
                  >领用</el-button
                ><el-dropdown @command="(a: string) => action(row, a)"
                  ><el-button link type="primary">更多⌄</el-button
                  ><template #dropdown
                    ><el-dropdown-menu
                      ><el-dropdown-item command="MOVE"
                        >申请迁移</el-dropdown-item
                      ><el-dropdown-item command="RETIRE"
                        >申请报废</el-dropdown-item
                      ><el-dropdown-item
                        v-if="['RUNNING', 'FAULT'].includes(row.status)"
                        command="MAINTAIN"
                        >进入维修</el-dropdown-item
                      ></el-dropdown-menu
                    ></template
                  ></el-dropdown
                ></template
              >
              <template v-if="resource === 'orders'"
                ><el-button
                  v-if="
                    auth.can('order:assign') &&
                    ['PENDING', 'ASSIGNED', 'IN_PROGRESS'].includes(row.status)
                  "
                  link
                  type="primary"
                  @click="action(row, 'assign')"
                  >分派</el-button
                ><el-button
                  v-if="
                    auth.can('order:handle') &&
                    own(row) &&
                    row.status === 'ASSIGNED'
                  "
                  link
                  type="primary"
                  @click="action(row, 'accept')"
                  >接单</el-button
                ><template
                  v-if="
                    auth.can('order:handle') &&
                    own(row) &&
                    row.status === 'IN_PROGRESS'
                  "
                  ><el-button link type="primary" @click="action(row, 'record')"
                    >记录</el-button
                  ><el-button link type="primary" @click="action(row, 'submit')"
                    >提交验收</el-button
                  ></template
                ><template
                  v-if="
                    auth.can('order:verify') &&
                    row.handler_id !== auth.user?.id &&
                    row.status === 'WAITING_REVIEW'
                  "
                  ><el-button
                    link
                    type="success"
                    @click="action(row, 'approve')"
                    >验收</el-button
                  ><el-button link type="danger" @click="action(row, 'reject')"
                    >驳回</el-button
                  ></template
                ></template
              >
              <template
                v-if="
                  resource === 'approvals' &&
                  auth.can('asset:approve') &&
                  row.status === 'PENDING' &&
                  row.applicant_id !== auth.user?.id
                "
                ><el-button link type="success" @click="action(row, 'approve')"
                  >通过</el-button
                ><el-button link type="danger" @click="action(row, 'reject')"
                  >驳回</el-button
                ></template
              >
              <template v-if="resource === 'tasks' && own(row)"
                ><el-button
                  v-if="
                    row.status === 'PENDING' && auth.can('inspection:execute')
                  "
                  link
                  type="primary"
                  @click="action(row, 'complete')"
                  >执行巡检</el-button
                ><el-button
                  v-if="row.status === 'COMPLETED' && auth.can('order:create')"
                  link
                  type="primary"
                  @click="action(row, 'workorder')"
                  >异常转工单</el-button
                ></template
              >
              <template
                v-if="resource === 'plans' && auth.can('inspection:manage')"
                ><el-button link type="primary" @click="action(row, 'generate')"
                  >生成</el-button
                ><el-button
                  link
                  type="primary"
                  @click="action(row, 'toggle')"
                  >{{ row.enabled ? "停用" : "启用" }}</el-button
                ></template
              >
              <template v-if="resource === 'alerts' && auth.can('alert:write')"
                ><el-button
                  v-if="row.status === 'OPEN'"
                  link
                  type="primary"
                  @click="action(row, 'acknowledge')"
                  >确认</el-button
                ><el-button
                  v-if="row.status !== 'RECOVERED'"
                  link
                  type="success"
                  @click="action(row, 'recover')"
                  >恢复</el-button
                ></template
              ><el-button
                v-if="resource === 'notifications' && !row.read_status"
                link
                type="primary"
                @click="action(row, 'read')"
                >标为已读</el-button
              >
            </div></template
          ></el-table-column
        ></el-table
      >
      <div class="pagination">
        <span>双击记录查看完整信息</span
        ><el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @change="load"
        />
      </div>
    </section>
    <el-dialog
      v-model="dialog"
      :title="dialogTitle"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
      ><el-alert
        v-if="!fields.length"
        title="确认后将记录审批结果并执行相应业务操作。"
        type="info"
        :closable="false"
      /><el-form label-position="top"
        ><el-form-item
          v-for="field in fields"
          :key="field.key"
          :label="field.label || labels[field.key] || field.key"
          :required="!field.optional && field.type !== 'switch'"
          ><el-select
            v-if="field.source"
            v-model="form[field.key]"
            filterable
            :multiple="field.multiple"
            :clearable="field.optional"
            style="width: 100%"
            ><el-option
              v-for="o in source(field.source)"
              :key="o.id"
              :value="o.id"
              :label="o.display_name || o.name" /></el-select
          ><el-select
            v-else-if="field.options"
            v-model="form[field.key]"
            style="width: 100%"
            ><el-option
              v-for="o in field.options"
              :key="o"
              :value="o"
              :label="states[o] || o" /></el-select
          ><el-switch
            v-else-if="field.type === 'switch'"
            v-model="form[field.key]" /><el-date-picker
            v-else-if="field.type === 'date'"
            v-model="form[field.key]"
            value-format="YYYY-MM-DD" /><el-input-number
            v-else-if="field.type === 'number'"
            v-model="form[field.key]"
            :min="0"
            :max="100" /><el-input
            v-else
            v-model="form[field.key]"
            :type="field.type || 'text'"
            :rows="4"
            :maxlength="field.type === 'textarea' ? 2000 : 200"
            :show-password="
              field.type === 'password'
            " /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="submit"
          >确认提交</el-button
        ></template
      ></el-dialog
    >
    <el-drawer v-model="drawer" :title="config.title + '详情'" size="620px"
      ><template v-if="detail"
        ><el-descriptions :column="1" border
          ><el-descriptions-item
            v-for="[key, value] in visibleDetail"
            :key="key"
            :label="labels[key] || key"
            >{{ display(key, value) }}</el-descriptions-item
          ></el-descriptions
        ><template v-if="detail.history"
          ><h3 class="detail-heading">操作时间线</h3>
          <el-timeline
            ><el-timeline-item
              v-for="h in detail.history"
              :key="h.id"
              :timestamp="h.created_at"
              placement="top"
              ><b
                >{{ h.operator_name || h.operator_id }} ·
                {{ states[h.action] || h.action }}</b
              >
              <p class="history-detail">{{ h.detail }}</p></el-timeline-item
            ></el-timeline
          ></template
        ><template v-if="detail.records?.length"
          ><h3 class="detail-heading">巡检结果</h3>
          <div v-for="r in detail.records" :key="r.id">
            <el-tag :type="r.abnormal ? 'danger' : 'success'">{{
              r.abnormal ? "存在异常" : "全部正常"
            }}</el-tag>
            <p>{{ r.description }}</p>
            <p v-for="(v, k) in JSON.parse(r.results)" :key="k">
              {{ k }}：{{ states[String(v)] }}
            </p>
          </div></template
        ></template
      ></el-drawer
    >
    <el-dialog v-model="importDialog" title="批量导入资产" width="650px"
      ><el-alert
        title="先校验全部数据，通过后再提交。每次最多 1000 行，编号必须唯一。关联编号可从各列表详情获取。"
        :closable="false"
        type="info"
      />
      <p><el-button @click="download(true)">下载 Excel 模板</el-button></p>
      <input
        type="file"
        accept=".xlsx"
        @change="
          (e) => {
            file = (e.target as HTMLInputElement).files?.[0];
            importResult = undefined;
          }
        "
      />
      <div v-if="importResult" style="margin-top: 20px">
        <el-alert
          :type="importResult.valid ? 'success' : 'error'"
          :title="
            importResult.committed
              ? '导入完成'
              : importResult.valid
                ? '校验通过，共 ' + importResult.count + ' 行'
                : '存在错误，未写入任何数据'
          "
          :closable="false"
        /><el-table
          v-if="importResult.errors.length"
          :data="importResult.errors"
          ><el-table-column prop="row" label="Excel 行号" /><el-table-column
            prop="message"
            label="错误原因"
        /></el-table>
      </div>
      <template #footer
        ><el-button
          :loading="checking"
          :disabled="!file"
          @click="importFile(false)"
          >校验文件</el-button
        ><el-button
          type="primary"
          :loading="checking"
          :disabled="!importResult?.valid || importResult?.committed"
          @click="importFile(true)"
          >确认导入</el-button
        ></template
      ></el-dialog
    > </template
  ><el-empty v-else description="页面不存在" />
</template>
