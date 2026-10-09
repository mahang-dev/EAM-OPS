import axios from "axios";
import { ElMessage } from "element-plus";
export const api = axios.create({ baseURL: "/api/v1", timeout: 20000 });
api.interceptors.request.use((c) => {
  const token = sessionStorage.getItem("token");
  if (token) c.headers.Authorization = `Bearer ${token}`;
  return c;
});
api.interceptors.response.use(
  (r) => r,
  (e) => {
    ElMessage.error(
      e.response?.data?.message || "连接失败，请检查服务是否启动",
    );
    if (e.response?.status === 401 && !e.config.url.includes("/auth/login")) {
      sessionStorage.removeItem("token");
      window.location.hash = "#/login";
    }
    return Promise.reject(e);
  },
);
export async function get(path: string, params?: unknown) {
  return (await api.get(path, { params })).data;
}
export async function post(path: string, data?: unknown) {
  return (await api.post(path, data)).data;
}
