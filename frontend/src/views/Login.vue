<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "../store";
const auth = useAuth(),
  router = useRouter(),
  username = ref(""),
  password = ref(""),
  busy = ref(false);
async function login() {
  if (!username.value || !password.value) return;
  busy.value = true;
  try {
    await auth.login(username.value, password.value);
    router.push(auth.can("dashboard:read") ? "/dashboard" : "/audit");
  } catch {
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="login-page">
    <div class="login-story">
      <div class="brand">
        <span class="brand-mark">E</span>
        <div>EAM / OPS<small>ENTERPRISE OPERATIONS</small></div>
      </div>
      <div class="story-content">
        <span class="eyebrow">让每一次运维，都有迹可循</span>
        <h1>连接设备。<br />协同运维。<br /><em>掌控每一刻。</em></h1>
        <p>
          从资产全生命周期到故障处理闭环，<br />构建清晰、可靠的企业运维工作空间。
        </p>
        <div class="story-tags">
          <span>资产管理</span><span>周期巡检</span><span>智能告警</span>
        </div>
      </div>
      <small>ENTERPRISE ASSET & MAINTENANCE OPERATIONS</small>
    </div>
    <div class="login-panel">
      <div class="login-form">
        <span class="eyebrow">欢迎回来 / WELCOME BACK</span>
        <h2>登录工作空间</h2>
        <p>使用你的企业账号，继续今天的工作。</p>
        <el-form label-position="top" @submit.prevent="login"
          ><el-form-item label="账号"
            ><el-input
              v-model="username"
              placeholder="请输入登录账号"
              autocomplete="username"
              size="large" /></el-form-item
          ><el-form-item label="密码"
            ><el-input
              v-model="password"
              placeholder="请输入密码"
              type="password"
              show-password
              autocomplete="current-password"
              size="large" /></el-form-item
          ><el-button
            native-type="submit"
            type="primary"
            size="large"
            :loading="busy"
            style="width: 100%"
            >登录系统 →</el-button
          ></el-form
        >
        <div class="login-note">
          账号由系统管理员分配。演示账号与密码请参考本地启动说明。
        </div>
      </div>
    </div>
  </div>
</template>
