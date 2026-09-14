#!/usr/bin/env node

const fs = require("fs");
const path = require("path");
const { execFileSync, spawnSync } = require("child_process");

const SEMVER_RE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;

function parseAndroidVersion(text) {
  const versionNameMatch = text.match(/versionName\s*=\s*"([^"]+)"/);
  const versionCodeMatch = text.match(/versionCode\s*=\s*(\d+)/);

  if (!versionNameMatch || !versionCodeMatch) {
    throw new Error("Android versionName/versionCode could not be read from app/build.gradle.kts");
  }

  const versionCode = Number(versionCodeMatch[1]);
  if (!Number.isSafeInteger(versionCode) || versionCode < 1) {
    throw new Error(`Invalid Android versionCode: ${versionCodeMatch[1]}`);
  }

  return {
    versionName: versionNameMatch[1],
    versionCode,
  };
}

function parseSemVer(version) {
  const match = version.match(SEMVER_RE);
  if (!match) {
    throw new Error(`Invalid SemVer version: ${version}`);
  }

  const prerelease = match[4]?.split(".") ?? [];
  if (prerelease.some((identifier) => /^\d+$/.test(identifier) && identifier.length > 1 && identifier.startsWith("0"))) {
    throw new Error(`Invalid SemVer version: ${version}`);
  }

  return {
    core: [BigInt(match[1]), BigInt(match[2]), BigInt(match[3])],
    prerelease,
  };
}

function compareSemVer(leftVersion, rightVersion) {
  const left = parseSemVer(leftVersion);
  const right = parseSemVer(rightVersion);

  for (let index = 0; index < left.core.length; index += 1) {
    if (left.core[index] > right.core[index]) return 1;
    if (left.core[index] < right.core[index]) return -1;
  }

  if (left.prerelease.length === 0 && right.prerelease.length > 0) return 1;
  if (left.prerelease.length > 0 && right.prerelease.length === 0) return -1;

  const length = Math.max(left.prerelease.length, right.prerelease.length);
  for (let index = 0; index < length; index += 1) {
    const leftIdentifier = left.prerelease[index];
    const rightIdentifier = right.prerelease[index];
    if (leftIdentifier === undefined) return -1;
    if (rightIdentifier === undefined) return 1;
    if (leftIdentifier === rightIdentifier) continue;

    const leftNumeric = /^\d+$/.test(leftIdentifier);
    const rightNumeric = /^\d+$/.test(rightIdentifier);
    if (leftNumeric && rightNumeric) {
      return BigInt(leftIdentifier) > BigInt(rightIdentifier) ? 1 : -1;
    }
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1;
    return leftIdentifier > rightIdentifier ? 1 : -1;
  }

  return 0;
}

function changelogContainsVersion(changelog, version) {
  const escaped = version.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^##\\s*\\[${escaped}\\]\\s*-\\s*.+$`, "m").test(changelog);
}

function resolveRelease({ refType, refName, current, previous, changelog, tagExists }) {
  const currentSemVer = parseSemVer(current.versionName);
  let shouldRelease = false;

  if (refType === "tag") {
    const expectedTag = `v${current.versionName}`;
    if (refName !== expectedTag) {
      throw new Error(`Release tag ${refName} does not match Android version ${expectedTag}`);
    }
    shouldRelease = true;
  } else if (refType === "branch" && refName === "main") {
    if (!previous || previous.versionName === current.versionName) {
      return {
        shouldRelease: false,
        releaseVersion: current.versionName,
        releaseTag: `v${current.versionName}`,
        isPrerelease: currentSemVer.prerelease.length > 0,
      };
    }

    if (compareSemVer(current.versionName, previous.versionName) <= 0) {
      throw new Error(`Android versionName must increase: ${previous.versionName} -> ${current.versionName}`);
    }
    if (current.versionCode <= previous.versionCode) {
      throw new Error(`Android versionCode must increase: ${previous.versionCode} -> ${current.versionCode}`);
    }
    if (tagExists) {
      throw new Error(`Release tag v${current.versionName} already exists`);
    }
    shouldRelease = true;
  }

  if (shouldRelease && !changelogContainsVersion(changelog, current.versionName)) {
    throw new Error(`CHANGELOG.md is missing a section for ${current.versionName}`);
  }

  return {
    shouldRelease,
    releaseVersion: current.versionName,
    releaseTag: `v${current.versionName}`,
    isPrerelease: currentSemVer.prerelease.length > 0,
  };
}

function writeOutputs(result) {
  const outputPath = process.env.GITHUB_OUTPUT;
  if (!outputPath) return;

  fs.appendFileSync(
    outputPath,
    [
      `should_release=${result.shouldRelease}`,
      `release_version=${result.releaseVersion}`,
      `release_tag=${result.releaseTag}`,
      `is_prerelease=${result.isPrerelease}`,
      "",
    ].join("\n")
  );
}

function main() {
  const root = process.cwd();
  const gradlePath = path.join(root, "app", "build.gradle.kts");
  const changelogPath = path.join(root, "CHANGELOG.md");
  const refType = process.env.GITHUB_REF_TYPE;
  const refName = process.env.GITHUB_REF_NAME;
  const beforeSha = process.env.BEFORE_SHA;

  if (!refType || !refName) {
    throw new Error("GITHUB_REF_TYPE and GITHUB_REF_NAME are required");
  }

  const current = parseAndroidVersion(fs.readFileSync(gradlePath, "utf8"));
  let previous = null;

  if (refType === "branch" && beforeSha && !/^0+$/.test(beforeSha)) {
    try {
      const previousGradle = execFileSync(
        "git",
        ["show", `${beforeSha}:app/build.gradle.kts`],
        { encoding: "utf8" }
      );
      previous = parseAndroidVersion(previousGradle);
    } catch (error) {
      throw new Error(`Could not read the previous Android version from ${beforeSha}: ${error.message}`);
    }
  }

  const releaseTag = `v${current.versionName}`;
  const tagExists = spawnSync(
    "git",
    ["rev-parse", "--verify", "--quiet", `refs/tags/${releaseTag}`],
    { stdio: "ignore" }
  ).status === 0;

  const result = resolveRelease({
    refType,
    refName,
    current,
    previous,
    changelog: fs.readFileSync(changelogPath, "utf8"),
    tagExists,
  });

  writeOutputs(result);
  console.log(
    result.shouldRelease
      ? `Release required: ${result.releaseTag}`
      : `No versionName bump detected; ${result.releaseTag} will not be published.`
  );
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

module.exports = {
  changelogContainsVersion,
  compareSemVer,
  parseAndroidVersion,
  parseSemVer,
  resolveRelease,
};
