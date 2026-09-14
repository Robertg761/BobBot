#!/usr/bin/env node
/**
 * Reads `app/build.gradle.kts` and emits:
 * - `version_name`
 * - `version_code`
 *
 * For GitHub Actions, we write these to $GITHUB_OUTPUT.
 */

const fs = require("fs");
const path = require("path");

const gradlePath = path.join(process.cwd(), "app", "build.gradle.kts");
const txt = fs.readFileSync(gradlePath, "utf8");

function match1(re, label) {
  const m = txt.match(re);
  if (!m) {
    console.error(`Failed to find ${label} in ${gradlePath}`);
    process.exit(1);
  }
  return m[1];
}

const versionName = match1(/versionName\s*=\s*"([^"]+)"/, "versionName");
const versionCodeRaw = match1(/versionCode\s*=\s*(\d+)/, "versionCode");
const versionCode = Number(versionCodeRaw);
if (!Number.isFinite(versionCode)) {
  console.error(`Invalid versionCode: ${versionCodeRaw}`);
  process.exit(1);
}

const out = process.env.GITHUB_OUTPUT;
if (out) {
  fs.appendFileSync(out, `version_name=${versionName}\n`);
  fs.appendFileSync(out, `version_code=${versionCode}\n`);
}

console.log(`versionName=${versionName}`);
console.log(`versionCode=${versionCode}`);

