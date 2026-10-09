import { createRouter, createWebHashHistory } from "vue-router";
import Login from "./views/Login.vue";
const Dashboard = () => import("./views/Dashboard.vue");
const ResourcePage = () => import("./views/ResourcePage.vue");
const Roles = () => import("./views/Roles.vue");
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/login", component: Login },
    { path: "/", redirect: "/dashboard" },
    { path: "/dashboard", component: Dashboard },
    { path: "/roles", component: Roles },
    { path: "/:resource", component: ResourcePage },
  ],
});
router.beforeEach((to) => {
  if (to.path != "/login" && !sessionStorage.getItem("token")) return "/login";
});
