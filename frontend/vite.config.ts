import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/v3": "http://127.0.0.1:8080",
      "/swagger-ui": "http://127.0.0.1:8080",
    },
  },
  build: { chunkSizeWarningLimit: 1400 },
});
