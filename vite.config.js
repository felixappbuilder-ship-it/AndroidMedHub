import { defineConfig } from 'vite';
import { resolve } from 'path';
import { glob } from 'glob';
import obfuscator from 'vite-plugin-javascript-obfuscator';

export default defineConfig({
  root: '.',                     // project root
  publicDir: 'public',           // static assets (CSS, images, data)
  plugins: [
    obfuscator({
      // These options give a good balance of protection and performance
      compact: true,
      controlFlowFlattening: false,      // set to true for stronger protection (but slower)
      deadCodeInjection: false,
      debugProtection: false,
      debugProtectionInterval: false,
      disableConsoleOutput: false,
      identifierNamesGenerator: 'hexadecimal',
      log: false,
      numbersToExpressions: false,
      renameGlobals: false,
      selfDefending: true,               // makes code tamper‑proof
      simplify: true,
      splitStrings: false,
      stringArray: true,
      stringArrayEncoding: ['base64'],
      stringArrayThreshold: 0.75,
      transformObjectKeys: false,
      unicodeEscapeSequence: false
    })
  ],
  build: {
    outDir: 'dist',                // output folder
    rollupOptions: {
      // Multi‑page input: include index.html and all HTML files in pages/
      input: Object.fromEntries(
        glob.sync(['index.html', 'pages/**/*.html']).map(file => [
          // Create a unique name for each entry (replace slashes with hyphens)
          file.replace(/\.html$/, '').replace(/\//g, '-'),
          resolve(__dirname, file)
        ])
      ),
      output: {
        // Disable hashing to keep filenames predictable (service worker friendly)
        entryFileNames: 'scripts/[name].js',
        chunkFileNames: 'scripts/[name].js',
        assetFileNames: 'css/[name].[ext]'
      }
    },
  },
  server: {
    port: 3001,
    open: '/index.html',
  },
});