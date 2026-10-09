<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from "vue";
import { useRouter } from "vue-router";
import * as echarts from "echarts";
import { get } from "../api";
import { states, tone } from "../labels";
import { useAuth } from "../store";
const auth = useAuth(),
  router = useRouter(),
  data = ref<any>(),
  busy = ref(false),
  error = ref(false),
  department = ref<number>(),
  departments = ref<any[]>([]);
const date = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const range = ref<[string, string]>([
    date(new Date(Date.now() - 29 * 86400000)),
    date(new Date()),
  ]),
  category = ref(),
  trend = ref(),
  status = ref();
let charts: echarts.ECharts[] = [];
function jump(resource: string, params: Record<string, any> = {}) {
  router.push({
    path: "/" + resource,
    query: {
      ...(resource === "orders" || resource === "tasks"
        ? { from: range.value[0], to: range.value[1] }
        : {}),
      ...(department.value ? { departmentId: department.value } : {}),
      ...params,
    },
  });
}
async function load() {
  busy.value = true;
  error.value = false;
  try {
    data.value = await get("/dashboard", {
      from: range.value[0],
      to: range.value[1],
      departmentId: department.value,
    });
    await nextTick();
    draw();
  } catch {
    error.value = true;
  } finally {
    busy.value = false;
  }
}
function draw() {
  charts.forEach((c) => c.dispose());
  const d = data.value;
  const colors = ["#2476e8", "#30b5a1", "#8b9cf4", "#f4b55d", "#db7d8e"];
  const base = {
    color: colors,
    textStyle: { fontFamily: "Microsoft YaHei, sans-serif", color: "#6a7b91" },
    tooltip: { trigger: "item" },
  };
  if (!category.value) return;
  const c = echarts.init(category.value);
  c.setOption({
    ...base,
    legend: { bottom: 0, icon: "circle" },
    series: [
      {
        type: "pie",
        radius: ["48%", "73%"],
        center: ["50%", "43%"],
        label: { show: false },
        itemStyle: { borderColor: "#fff", borderWidth: 4, borderRadius: 6 },
        data: d.categories,
      },
    ],
  });
  const t = echarts.init(trend.value);
  t.setOption({
    ...base,
    tooltip: { trigger: "axis" },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: "category",
      data: d.trend.map((x: any) => x.name),
      axisLine: { lineStyle: { color: "#e4eaf1" } },
      axisTick: { show: false },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { color: "#eef2f7", type: "dashed" } },
    },
    series: [
      {
        type: "line",
        smooth: true,
        data: d.trend.map((x: any) => x.value),
        symbolSize: 7,
        lineStyle: { width: 3 },
        areaStyle: { color: "rgba(36,118,232,.09)" },
      },
    ],
  });
  const s = echarts.init(status.value);
  s.setOption({
    ...base,
    grid: { left: 75, right: 24, top: 10, bottom: 30 },
    xAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { color: "#eef2f7" } },
    },
    yAxis: {
      type: "category",
      data: d.orderStatus.map((x: any) => states[x.name] || x.name),
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [
      {
        type: "bar",
        barWidth: 18,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        data: d.orderStatus.map((x: any) => x.value),
      },
    ],
  });
  charts = [c, t, s];
}
const resize = () => charts.forEach((c) => c.resize());
onMounted(async () => {
  if (!auth.user) await auth.load();
  if (!auth.can("dashboard:read")) return;
  departments.value = (await get("/options")).departments;
  await load();
  window.addEventListener("resize", resize);
});
onUnmounted(() => {
  charts.forEach((c) => c.dispose());
  window.removeEventListener("resize", resize);
});
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">OPERATIONS OVERVIEW</div>
      <h1>运维总览<span class="heading-dot"></span></h1>
      <p>设备运行与运维工作的全局视图</p>
    </div>
    <div class="heading-tools">
      <el-select
        v-model="department"
        clearable
        placeholder="全部授权部门"
        style="width: 160px"
        @change="load"
        ><el-option
          v-for="d in departments"
          :key="d.id"
          :value="d.id"
          :label="d.name" /></el-select
      ><el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        :clearable="false"
        style="width: 250px"
        @change="load"
      /><el-button @click="load">刷新</el-button>
    </div>
  </div>
  <el-alert
    v-if="error"
    title="数据加载失败，可点击刷新重试"
    type="error"
    :closable="false"
  />
  <div v-loading="busy" v-if="data">
    <div class="metric-grid">
      <button class="metric-card" @click="jump('assets')">
        <span class="metric-icon blue">▦</span
        ><span class="metric-label">在册设备资产</span
        ><strong>{{ data.assets }}<small>台</small></strong
        ><span class="metric-foot">当前有效资产 <b>查看台账 →</b></span></button
      ><button class="metric-card" @click="jump('assets', { status: 'FAULT' })">
        <span class="metric-icon orange">△</span
        ><span class="metric-label">故障设备</span
        ><strong>{{ data.faults }}<small>台</small></strong
        ><span class="metric-foot"
          >故障资产占比
          <b
            >{{
              data.assets ? ((data.faults / data.assets) * 100).toFixed(1) : 0
            }}%</b
          ></span
        ></button
      ><button class="metric-card" @click="jump('orders')">
        <span class="metric-icon purple">◷</span
        ><span class="metric-label">待闭环工单</span
        ><strong>{{ data.openOrders }}<small>单</small></strong
        ><span class="metric-foot"
          >其中超时
          <b class="text-orange">{{ data.overdueOrders }} 单 →</b></span
        ></button
      ><button class="metric-card" @click="jump('tasks')">
        <span class="metric-icon teal">✓</span
        ><span class="metric-label">巡检完成率</span
        ><strong
          >{{
            data.tasks
              ? Math.round((data.completedTasks / data.tasks) * 100)
              : 0
          }}<small>%</small></strong
        ><span class="metric-foot"
          >已完成 {{ data.completedTasks }} / {{ data.tasks }}
          <b>查看任务 →</b></span
        >
      </button>
    </div>
    <div class="dashboard-row">
      <section class="panel trend-panel">
        <div class="panel-title">
          <h3>工单趋势</h3>
          <span>按创建日期统计</span>
        </div>
        <div ref="trend" class="chart"></div>
        <div class="chart-caption">
          <span
            >本期工单 <b>{{ data.orders }}</b></span
          ><span
            >平均处理时长 <b>{{ data.averageHours }} h</b></span
          >
        </div>
      </section>
      <section class="panel">
        <div class="panel-title">
          <h3>设备类型分布</h3>
          <span>当前资产</span>
        </div>
        <div ref="category" class="chart"></div>
      </section>
    </div>
    <div class="dashboard-row">
      <section class="panel trend-panel">
        <div class="panel-title">
          <h3><span class="live-dot orange-dot"></span> 待处理告警</h3>
          <el-button link type="primary" @click="jump('alerts')"
            >查看全部 →</el-button
          >
        </div>
        <el-table :data="data.alerts" empty-text="暂无活动告警，设备状态平稳"
          ><el-table-column
            prop="title"
            label="告警内容"
            min-width="220" /><el-table-column label="级别" width="90"
            ><template #default="{ row }"
              ><el-tag :type="tone(row.level)" size="small">{{
                states[row.level]
              }}</el-tag></template
            ></el-table-column
          ><el-table-column prop="last_seen_at" label="最近发生" width="185"
        /></el-table>
      </section>
      <section class="panel">
        <div class="panel-title"><h3>工单状态分布</h3></div>
        <div ref="status" class="chart small-chart"></div>
      </section>
    </div>
    <div class="method-note">
      统计口径：资产为当前快照；工单按创建日期筛选，平均处理时长为已关闭工单创建至关闭的耗时；巡检按计划周期筛选。统计缓存最长
      30 秒。
    </div>
  </div>
</template>
