<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, get } from "../api";
import { ElMessage } from "element-plus";
const rows = ref<any[]>([]),
  busy = ref(false);
const permissions: Record<string, string> = {
  "asset:read": "查看资产",
  "asset:write": "维护资产",
  "asset:approve": "审批资产",
  "inspection:read": "查看巡检",
  "inspection:execute": "执行巡检",
  "inspection:manage": "管理巡检",
  "order:read": "查看工单",
  "order:create": "创建工单",
  "order:handle": "处理工单",
  "order:assign": "分派工单",
  "order:verify": "验收工单",
  "alert:read": "查看告警",
  "alert:write": "处理告警",
  "alert:manage": "配置规则",
  "dashboard:read": "运维看板",
  "audit:read": "审计日志",
};
async function load() {
  rows.value = (await get("/roles")).map((x: any) => ({
    ...x,
    selected: JSON.parse(x.permissions),
  }));
}
async function save(row: any) {
  busy.value = true;
  try {
    await api.put("/roles/" + row.code, { permissions: row.selected });
    ElMessage.success("权限已更新，相关用户需重新登录");
  } catch {
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">ACCESS CONTROL</div>
      <h1>角色权限</h1>
      <p>功能授权与部门数据范围共同约束每一次操作</p>
    </div>
  </div>
  <div class="role-grid">
    <section v-for="row in rows" :key="row.code" class="panel">
      <div class="panel-title">
        <h3>{{ row.name }}</h3>
        <span>{{ row.code }}</span>
      </div>
      <p v-if="row.code === 'ADMIN'">系统管理员拥有全局管理权限。</p>
      <template v-else
        ><el-checkbox-group v-model="row.selected"
          ><el-checkbox
            v-for="(label, code) in permissions"
            :key="code"
            :value="code"
            :disabled="row.code === 'AUDITOR' && code !== 'audit:read'"
            >{{ label }}</el-checkbox
          ></el-checkbox-group
        ><el-button type="primary" plain :loading="busy" @click="save(row)"
          >保存权限</el-button
        ></template
      >
    </section>
  </div>
</template>
