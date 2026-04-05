import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const scriptFilePath = fileURLToPath(import.meta.url);
const scriptDirPath = path.dirname(scriptFilePath);
const backendRootPath = path.resolve(scriptDirPath, "..");
const outputDirPath = path.join(backendRootPath, "dist");
const backendBundlePath = path.join(outputDirPath, "server.cjs");
const legacyBunExecutablePath = path.join(outputDirPath, "sprint-sync-windows.exe");
const uiDistPath = path.resolve(backendRootPath, "..", "ui", "dist");
const packagedUiPath = path.join(outputDirPath, "ui");

if (!fs.existsSync(path.join(uiDistPath, "index.html"))) {
  throw new Error(`Expected frontend build output at ${uiDistPath}, but index.html was not found.`);
}

fs.mkdirSync(outputDirPath, { recursive: true });
removePathIfExists(backendBundlePath);
removePathIfExists(legacyBunExecutablePath);
removePathIfExists(packagedUiPath);

await build({
  entryPoints: [path.join(backendRootPath, "src", "server.mjs")],
  outfile: backendBundlePath,
  bundle: true,
  platform: "node",
  format: "cjs",
  target: ["node20"],
  minify: true,
  sourcemap: false,
  logLevel: "info",
});

if (!fs.existsSync(backendBundlePath)) {
  throw new Error(`Expected bundled backend at ${backendBundlePath}, but it was not found.`);
}

fs.cpSync(uiDistPath, packagedUiPath, { recursive: true });

if (!fs.existsSync(path.join(packagedUiPath, "index.html"))) {
  throw new Error(`Expected packaged frontend index at ${packagedUiPath}, but it was not found.`);
}

console.log(`[windows-backend] Packaged backend bundle: ${backendBundlePath}`);
console.log(`[windows-backend] Packaged frontend assets: ${packagedUiPath}`);

function removePathIfExists(targetPath) {
  if (!fs.existsSync(targetPath)) {
    return;
  }

  try {
    fs.rmSync(targetPath, { recursive: true, force: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown error";
    throw new Error(`Unable to remove existing path at ${targetPath}. Close any process using it and retry. ${message}`);
  }
}