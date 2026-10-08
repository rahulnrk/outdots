import path from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@mini/domain': path.resolve(import.meta.dirname, '../../packages/domain/src')
    }
  }
});
