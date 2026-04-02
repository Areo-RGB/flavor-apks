import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

function run(command, args) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.error) {
    throw result.error;
  }
  return result;
}

function fail(message, detail = '') {
  console.error(message);
  if (detail.trim().length > 0) {
    console.error(detail.trim());
  }
  process.exit(1);
}

function resolveConnectedDeviceId(expectedDeviceId, onlineDeviceIds) {
  if (onlineDeviceIds.has(expectedDeviceId)) {
    return expectedDeviceId;
  }

  const tlsPrefix = `adb-${expectedDeviceId}-`;
  for (const deviceId of onlineDeviceIds) {
    if (deviceId.startsWith(tlsPrefix) && deviceId.includes('._adb-tls-connect._tcp')) {
      return deviceId;
    }
  }

  return null;
}

const installs = [
  {
    label: 'Pad 7 host',
    deviceId: process.env.ADB_DEVICE_PAD ?? '4c637b9e',
    packageName: 'sync.sprint.host.xiaomi',
    apkPath: resolve(
      process.cwd(),
      'android',
      'app',
      'build',
      'outputs',
      'apk',
      'hostXiaomi',
      'debug',
      'app-hostXiaomi-debug.apk',
    ),
  },
  {
    label: 'Pixel 7 client',
    deviceId: process.env.ADB_DEVICE_PIXEL ?? '31071FDH2008FK',
    packageName: 'sync.sprint.client.pixel',
    apkPath: resolve(
      process.cwd(),
      'android',
      'app',
      'build',
      'outputs',
      'apk',
      'clientPixel',
      'debug',
      'app-clientPixel-debug.apk',
    ),
  },
  {
    label: 'OnePlus client',
    deviceId: process.env.ADB_DEVICE_ONEPLUS ?? 'DMIFHU7HUG9PKVVK',
    packageName: 'sync.sprint.client.oneplus',
    apkPath: resolve(
      process.cwd(),
      'android',
      'app',
      'build',
      'outputs',
      'apk',
      'clientOneplus',
      'debug',
      'app-clientOneplus-debug.apk',
    ),
  },
  {
    label: 'Huawei client',
    deviceId: process.env.ADB_DEVICE_HUAWEI ?? 'UBV0218316007905',
    packageName: 'sync.sprint.client.huawei',
    apkPath: resolve(
      process.cwd(),
      'android',
      'app',
      'build',
      'outputs',
      'apk',
      'clientHuawei',
      'debug',
      'app-clientHuawei-debug.apk',
    ),
  },
  {
    label: 'Xiaomi client',
    deviceId: process.env.ADB_DEVICE_XIAOMI ?? '29fec8f8',
    packageName: 'sync.sprint.client.xiaomi',
    apkPath: resolve(
      process.cwd(),
      'android',
      'app',
      'build',
      'outputs',
      'apk',
      'clientXiaomi',
      'debug',
      'app-clientXiaomi-debug.apk',
    ),
  },
];

const missing = installs.filter((entry) => !existsSync(entry.apkPath));
if (missing.length > 0) {
  fail(
    `Missing APK files:\n${missing.map((entry) => `- ${entry.label}: ${entry.apkPath}`).join('\n')}\nRun "npm run build:debug:device-flavors" first.`,
  );
}

const adbDevices = run('adb', ['devices']);
if (adbDevices.status !== 0) {
  fail('Failed to run "adb devices".', adbDevices.stderr);
}

const onlineDevices = new Set(
  adbDevices.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.endsWith('\tdevice'))
    .map((line) => line.split('\t')[0]),
);

for (const entry of installs) {
  const connectedDeviceId = resolveConnectedDeviceId(entry.deviceId, onlineDevices);
  if (!connectedDeviceId) {
    console.log(
      `Skipping ${entry.label}: device not online (${entry.deviceId}). ` +
      `Online devices: ${Array.from(onlineDevices).join(', ') || '(none)'}`,
    );
    continue;
  }

  if (connectedDeviceId !== entry.deviceId) {
    console.log(`Resolved ${entry.label} ${entry.deviceId} -> ${connectedDeviceId}`);
  }

  console.log(`Installing ${entry.label} on ${connectedDeviceId}...`);
  const installResult = run('adb', ['-s', connectedDeviceId, 'install', '-r', entry.apkPath]);
  const output = `${installResult.stdout}\n${installResult.stderr}`.trim();
  const success = installResult.status === 0 && output.includes('Success');
  if (!success) {
    fail(`Install failed for ${entry.label} (${connectedDeviceId}).`, output);
  }
  console.log(`Install success for ${entry.label}.`);

  console.log(`Launching ${entry.label} (${entry.packageName})...`);
  const launchResult = run('adb', [
    '-s',
    connectedDeviceId,
    'shell',
    'monkey',
    '-p',
    entry.packageName,
    '-c',
    'android.intent.category.LAUNCHER',
    '1',
  ]);
  const launchOutput = `${launchResult.stdout}\n${launchResult.stderr}`.trim();
  const launchSuccess =
    launchResult.status === 0 &&
    !launchOutput.includes('No activities found to run');
  if (!launchSuccess) {
    fail(`Launch failed for ${entry.label} (${connectedDeviceId}).`, launchOutput);
  }
  console.log(`Launch success for ${entry.label}.`);
}

console.log('Install/launch pass finished for all currently online device-flavor targets.');
