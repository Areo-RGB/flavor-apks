import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

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

function canRun(command) {
  const probe = spawnSync(command, ['version'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return !probe.error;
}

function resolveAdbCommand() {
  const candidates = [process.env.ADB_BIN, process.env.ADB_PATH, 'adb', 'adb.exe'];
  const sdkRoots = [process.env.ANDROID_SDK_ROOT, process.env.ANDROID_HOME].filter(Boolean);
  for (const sdkRoot of sdkRoots) {
    candidates.push(resolve(sdkRoot, 'platform-tools', 'adb'));
    candidates.push(resolve(sdkRoot, 'platform-tools', 'adb.exe'));
  }

  if (process.platform === 'linux') {
    const windowsUsers = new Set([process.env.USER].filter(Boolean));
    const userProfile = process.env.USERPROFILE;
    if (userProfile) {
      const segments = userProfile.replace(/\\/g, '/').split('/').filter(Boolean);
      if (segments.length > 0) {
        windowsUsers.add(segments[segments.length - 1]);
      }
    }
    for (const user of windowsUsers) {
      candidates.push(`/mnt/c/Users/${user}/AppData/Local/Android/Sdk/platform-tools/adb.exe`);
    }
  }

  for (const candidate of candidates) {
    if (!candidate) {
      continue;
    }
    if (candidate.includes('/') || candidate.includes('\\')) {
      if (existsSync(candidate)) {
        return candidate;
      }
      continue;
    }
    if (canRun(candidate)) {
      return candidate;
    }
  }

  fail(
    'Unable to locate adb/adb.exe. Set ADB_BIN to your adb path (for WSL usually /mnt/c/Users/<you>/AppData/Local/Android/Sdk/platform-tools/adb.exe).',
  );
}

const adbCommand = resolveAdbCommand();

function toAdbFilePath(filePath) {
  if (!adbCommand.toLowerCase().endsWith('.exe')) {
    return filePath;
  }

  const normalized = filePath.replace(/\\/g, '/');
  const match = normalized.match(/^\/mnt\/([a-zA-Z])\/(.*)$/);
  if (!match) {
    return filePath;
  }

  return `${match[1].toUpperCase()}:\\${match[2].replace(/\//g, '\\')}`;
}

const appId = 'sync.sprint';
const apkCandidates = [
  resolve(process.cwd(), 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
  // Legacy fallback for older custom Gradle layout.
  resolve(process.cwd(), 'build', 'app', 'outputs', 'apk', 'release', 'app-release.apk'),
];
const apkPath = apkCandidates.find((path) => existsSync(path));

if (!apkPath) {
  fail(
    `Release APK not found in expected paths:\n- ${apkCandidates.join('\n- ')}\nRun "npm run build:release:apk" first.`,
  );
}

const devicesResult = run(adbCommand, ['devices']);
if (devicesResult.status !== 0) {
  fail('Failed to run "adb devices". Ensure adb is installed and in PATH.', devicesResult.stderr);
}

const lines = devicesResult.stdout
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter((line) => line.length > 0 && !line.startsWith('List of devices attached'));

const readyDeviceIds = lines
  .filter((line) => /\tdevice$/.test(line))
  .map((line) => line.split('\t')[0]);

const ignored = lines.filter((line) => !/\tdevice$/.test(line));
if (ignored.length > 0) {
  console.log(`Ignoring non-ready entries: ${ignored.join(', ')}`);
}

if (readyDeviceIds.length === 0) {
  fail('No ready Android devices found. Connect devices and run "adb devices".');
}

let failedInstalls = 0;
let failedLaunches = 0;
for (const deviceId of readyDeviceIds) {
  console.log(`Installing release APK on ${deviceId}...`);
  const installResult = run(adbCommand, ['-s', deviceId, 'install', '-r', toAdbFilePath(apkPath)]);
  const output = `${installResult.stdout}\n${installResult.stderr}`.trim();

  if (installResult.status !== 0 || !output.includes('Success')) {
    failedInstalls += 1;
    console.error(`Install failed on ${deviceId}.`);
    if (output.length > 0) {
      console.error(output);
    }
    continue;
  }

  console.log(`Install success on ${deviceId}.`);

  console.log(`Launching ${appId} on ${deviceId}...`);
  const launchResult = run(adbCommand, [
    '-s',
    deviceId,
    'shell',
    'monkey',
    '-p',
    appId,
    '-c',
    'android.intent.category.LAUNCHER',
    '1',
  ]);
  const launchOutput = `${launchResult.stdout}\n${launchResult.stderr}`.trim();
  const launchSucceeded =
    launchResult.status === 0 &&
    !launchOutput.includes('No activities found') &&
    !launchOutput.includes('Error');

  if (!launchSucceeded) {
    failedLaunches += 1;
    console.error(`Launch failed on ${deviceId}.`);
    if (launchOutput.length > 0) {
      console.error(launchOutput);
    }
    continue;
  }

  console.log(`Launch success on ${deviceId}.`);
}

if (failedInstalls > 0) {
  fail(`${failedInstalls} device install(s) failed.`);
}

if (failedLaunches > 0) {
  fail(`${failedLaunches} device launch(es) failed.`);
}

console.log(`Installed and launched release APK on ${readyDeviceIds.length} device(s).`);
