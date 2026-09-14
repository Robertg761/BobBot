#!/usr/bin/env node
/**
 * Aligns Android version metadata in app/build.gradle.kts to a release version.
 *
 * If versionName already matches the release version, versionCode is preserved.
 * If versionName changes, versionCode is incremented by one so direct-download
 * Android updates can install over the previous release.
 *
 * Usage:
 *   node scripts/sync_android_version.js 1.7.5
 */

const fs = require("fs");
const path = require("path");

const releaseVersion = process.argv[2];
if (!releaseVersion) {
  console.error("Usage: node scripts/sync_android_version.js <version>");
  process.exit(2);
}

const semverMatch = releaseVersion.match(
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/
);
const prereleaseIdentifiers = semverMatch?.[4]?.split(".") ?? [];
const hasInvalidNumericPrerelease = prereleaseIdentifiers.some(
  (identifier) => /^\d+$/.test(identifier) && identifier.length > 1 && identifier.startsWith("0")
);

if (!semverMatch || hasInvalidNumericPrerelease) {
  console.error(`Invalid release version: ${releaseVersion}`);
  process.exit(2);
}

const gradlePath = path.join(process.cwd(), "app", "build.gradle.kts");
const original = fs.readFileSync(gradlePath, "utf8");

const versionNameMatch = original.match(/versionName\s*=\s*"([^"]+)"/);
const versionCodeMatch = original.match(/versionCode\s*=\s*(\d+)/);

if (!versionNameMatch) {
  console.error(`Failed to find versionName in ${gradlePath}`);
  process.exit(1);
}

if (!versionCodeMatch) {
  console.error(`Failed to find versionCode in ${gradlePath}`);
  process.exit(1);
}

const currentVersionName = versionNameMatch[1];
const currentVersionCode = Number(versionCodeMatch[1]);
if (!Number.isSafeInteger(currentVersionCode) || currentVersionCode < 1) {
  console.error(`Invalid versionCode: ${versionCodeMatch[1]}`);
  process.exit(1);
}

const nextVersionCode =
  currentVersionName === releaseVersion ? currentVersionCode : currentVersionCode + 1;

const updated = original
  .replace(/versionCode\s*=\s*\d+/, `versionCode = ${nextVersionCode}`)
  .replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${releaseVersion}"`);

fs.writeFileSync(gradlePath, updated);

console.log(
  `Android version metadata aligned: versionName ${currentVersionName} -> ${releaseVersion}, ` +
    `versionCode ${currentVersionCode} -> ${nextVersionCode}`
);
