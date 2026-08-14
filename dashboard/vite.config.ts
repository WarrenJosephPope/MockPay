import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The SPA is built into the Spring Boot jar rather than served separately: one
// deployable artefact, same origin, so the session cookie just works and there is
// no CORS to configure.
export default defineConfig({
  plugins: [react()],
  build: {
    // Straight into the packaged classes. Maven has already copied
    // src/main/resources/static by the time this runs.
    outDir: '../target/classes/static',
    // Must NOT wipe the directory: mockpay.js, the demo checkout and the simulated
    // challenge pages live there too and are not ours to delete.
    emptyOutDir: false,
    assetsDir: 'dashboard-assets',
  },
  server: {
    port: 5173,
    // `npm run dev` gives hot reload while proxying every API path to the running
    // gateway, so the cookie stays same-origin from the browser's point of view.
    proxy: {
      '/dashboard': 'http://localhost:8088',
      '/v1': 'http://localhost:8088',
      '/demo': 'http://localhost:8088',
      '/webhook-sink': 'http://localhost:8088',
    },
  },
});
