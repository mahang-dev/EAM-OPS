import { defineStore } from "pinia";
import { ref } from "vue";
import { get, post } from "./api";
export interface User {
  id: number;
  departmentId: number;
  username: string;
  displayName: string;
  role: string;
  permissions: string[];
}
export const useAuth = defineStore("auth", () => {
  const user = ref<User>();
  const demo = ref(false);
  const can = (p: string) =>
    !!user.value &&
    (user.value.permissions.includes("*") ||
      user.value.permissions.includes(p));
  async function load() {
    user.value = await get("/auth/me");
    demo.value = (await get("/capabilities")).demo;
  }
  async function login(username: string, password: string) {
    const r = await post("/auth/login", { username, password });
    sessionStorage.setItem("token", r.token);
    user.value = r.user;
    await load();
  }
  async function logout() {
    try {
      await post("/auth/logout");
    } finally {
      sessionStorage.removeItem("token");
      user.value = undefined;
    }
  }
  return { user, demo, can, load, login, logout };
});
