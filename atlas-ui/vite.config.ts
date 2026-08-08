import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Dev proxy: the browser talks to Vite (5173), which forwards /api to atlas-core (8080).
// Same-origin from the browser's view, so no CORS config is needed anywhere in dev.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
