<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "./store";
import { get, post } from "./api";
import { ElMessage, ElMessageBox } from "element-plus";
import { states } from "./labels";
const auth = useAuth(),
  route = useRoute(),
  router = useRouter(),
  unread = ref(0);
const groups = [
  {
    name: "工作空间",
    links: [
      ["dashboard", "运维总览", "dashboard:read", "◈"],
      ["assets", "设备资产", "asset:read", "▦"],
      ["approvals", "资产审批", "asset:read", "▤"],
    ],
  },
  {
    name: "运维中心",
    links: [
      ["tasks", "巡检任务", "inspection:read", "◎"],
      ["plans", "巡检计划", "inspection:manage", "▣"],
      ["templates", "检查模板", "inspection:manage", "▧"],
      ["orders", "故障工单", "order:read", "◷"],
      ["alerts", "告警中心", "alert:read", "△"],
      ["rules", "告警规则", "alert:manage", "⌁"],
    ],
  },
  {
    name: "基础配置",
    links: [
      ["locations", "机房与机柜", "asset:read", "▥"],
      ["categories", "设备分类", "asset:read", "◇"],
      ["users", "用户管理", "system:manage", "♙"],
      ["departments", "部门管理", "system:manage", "⊞"],
      ["roles", "角色权限", "system:manage", "⚿"],
      ["audit", "审计日志", "audit:read", "≡"],
    ],
  },
];
const title = computed(
  () =>
    groups
      .flatMap((g) => g.links)
      .find((x) => route.path === "/" + x[0])?.[1] ||
    (route.path === "/notifications" ? "通知中心" : "工作空间"),
);
async function init() {
  if (sessionStorage.getItem("token")) {
    try {
      await auth.load();
      unread.value = (await get("/notifications", { size: 100 })).items.filter(
        (x: any) => !x.read_status,
      ).length;
      if (route.path === "/dashboard" && !auth.can("dashboard:read"))
        router.replace("/audit");
    } catch {}
  }
}
onMounted(init);
watch(
  () => route.path,
  async (p) => {
    if (p != "/login" && !auth.user) await init();
    if (p != "/login" && auth.user)
      try {
        unread.value = (
          await get("/notifications", { size: 100 })
        ).items.filter((x: any) => !x.read_status).length;
      } catch {}
  },
);
async function logout() {
  await auth.logout();
  router.push("/login");
}
async function password() {
  try {
    const old = await ElMessageBox.prompt("请输入当前密码", "修改密码", {
      inputType: "password",
    });
    const next = await ElMessageBox.prompt(
      "请输入新密码（10–72 字符）",
      "修改密码",
      {
        inputType: "password",
        inputValidator: (v) =>
          (!!v && v.length >= 10 && v.length <= 72) || "密码需要 10–72 个字符",
      },
    );
    await post("/auth/password", {
      oldPassword: old.value,
      password: next.value,
    });
    ElMessage.success("密码已修改，请重新登录");
    await logout();
  } catch {}
}
</script>
<template>
  <router-view v-if="route.path === '/login'" />
  <div v-else class="shell">
    <aside class="sidebar">
      <a class="brand" href="#/dashboard"
        ><span class="brand-mark">E</span>
        <div>
          EAM<span class="brand-light"> / OPS</span
          ><small>设备资产与智能运维</small>
        </div></a
      >
      <div class="workspace">企业工作空间 <span>ENTERPRISE</span></div>
      <nav>
        <section v-for="group in groups" :key="group.name">
          <div
            v-if="group.links.some((x) => auth.can(x[2]!))"
            class="nav-caption"
          >
            {{ group.name }}
          </div>
          <template v-for="link in group.links" :key="link[0]"
            ><router-link
              v-if="auth.can(link[2]!)"
              :to="'/' + link[0]"
              class="nav-link"
              ><span>{{ link[3] }}</span
              >{{ link[1]
              }}<b v-if="route.path === '/' + link[0]">•</b></router-link
            ></template
          >
        </section>
      </nav>
      <div class="sidebar-footer">
        <span class="live-dot"></span> EAM-OPS v1.0
        <small>可追踪 · 可协作 · 可验证</small>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          工作空间 <span>/</span> <strong>{{ title }}</strong>
        </div>
        <div class="top-actions">
          <span v-if="auth.demo" class="demo-label">模拟演示环境</span
          ><el-badge :value="unread" :hidden="!unread" :max="99"
            ><el-button text @click="router.push('/notifications')"
              >通知</el-button
            ></el-badge
          ><el-dropdown
            @command="(c: string) => (c === 'logout' ? logout() : password())"
            ><div class="user-chip">
              <span class="avatar">{{
                auth.user?.displayName?.slice(0, 1)
              }}</span>
              <div>
                {{ auth.user?.displayName
                }}<small>{{ states[auth.user?.role || ""] }}</small>
              </div>
              <span>⌄</span>
            </div>
            <template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="password">修改密码</el-dropdown-item
                ><el-dropdown-item command="logout"
                  >退出登录</el-dropdown-item
                ></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </div>
      </header>
      <main><router-view :key="route.path" /></main>
      <footer class="page-footer">
        EAM-OPS · Enterprise Asset & Maintenance Operations
        <span>业务时间：Asia / Shanghai</span>
      </footer>
    </div>
  </div>
</template>
