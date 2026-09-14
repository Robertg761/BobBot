const test = require("node:test");
const assert = require("node:assert/strict");

const {
  compareSemVer,
  parseAndroidVersion,
  resolveRelease,
} = require("../resolve_release_trigger");

const changelog = (version) => `# Changelog\n\n## [${version}] - 2026-07-18\n\n### Changed\n- Update\n`;

test("reads Android version metadata", () => {
  assert.deepEqual(
    parseAndroidVersion('versionCode = 23\nversionName = "1.8.1"'),
    { versionCode: 23, versionName: "1.8.1" }
  );
});

test("rejects a non-positive Android versionCode", () => {
  assert.throws(
    () => parseAndroidVersion('versionCode = 0\nversionName = "1.8.1"'),
    /Invalid Android versionCode/
  );
});

test("orders stable and prerelease SemVer values", () => {
  assert.equal(compareSemVer("1.8.1", "1.8.0"), 1);
  assert.equal(compareSemVer("1.8.1", "1.8.1-rc.1"), 1);
  assert.equal(compareSemVer("1.8.1-rc.10", "1.8.1-rc.2"), 1);
  assert.equal(compareSemVer("1.8.0", "1.8.1"), -1);
});

test("a main version bump starts a release", () => {
  assert.deepEqual(
    resolveRelease({
      refType: "branch",
      refName: "main",
      current: { versionName: "1.8.1", versionCode: 23 },
      previous: { versionName: "1.8.0", versionCode: 22 },
      changelog: changelog("1.8.1"),
      tagExists: false,
    }),
    {
      shouldRelease: true,
      releaseVersion: "1.8.1",
      releaseTag: "v1.8.1",
      isPrerelease: false,
    }
  );
});

test("a normal main push does not start a release", () => {
  const result = resolveRelease({
    refType: "branch",
    refName: "main",
    current: { versionName: "1.8.1", versionCode: 24 },
    previous: { versionName: "1.8.1", versionCode: 23 },
    changelog: changelog("1.8.1"),
    tagExists: false,
  });

  assert.equal(result.shouldRelease, false);
});

test("a matching manual tag starts a release", () => {
  const result = resolveRelease({
    refType: "tag",
    refName: "v1.8.1-rc.1",
    current: { versionName: "1.8.1-rc.1", versionCode: 23 },
    previous: null,
    changelog: changelog("1.8.1-rc.1"),
    tagExists: true,
  });

  assert.equal(result.shouldRelease, true);
  assert.equal(result.isPrerelease, true);
});

test("rejects a versionName bump without a higher versionCode", () => {
  assert.throws(
    () => resolveRelease({
      refType: "branch",
      refName: "main",
      current: { versionName: "1.8.1", versionCode: 22 },
      previous: { versionName: "1.8.0", versionCode: 22 },
      changelog: changelog("1.8.1"),
      tagExists: false,
    }),
    /versionCode must increase/
  );
});

test("rejects a release without matching changelog notes", () => {
  assert.throws(
    () => resolveRelease({
      refType: "branch",
      refName: "main",
      current: { versionName: "1.8.1", versionCode: 23 },
      previous: { versionName: "1.8.0", versionCode: 22 },
      changelog: changelog("1.8.0"),
      tagExists: false,
    }),
    /CHANGELOG.md is missing/
  );
});

test("rejects an automatic release when its tag already exists", () => {
  assert.throws(
    () => resolveRelease({
      refType: "branch",
      refName: "main",
      current: { versionName: "1.8.1", versionCode: 23 },
      previous: { versionName: "1.8.0", versionCode: 22 },
      changelog: changelog("1.8.1"),
      tagExists: true,
    }),
    /already exists/
  );
});

test("rejects a tag that disagrees with the Android version", () => {
  assert.throws(
    () => resolveRelease({
      refType: "tag",
      refName: "v1.8.0",
      current: { versionName: "1.8.1", versionCode: 23 },
      previous: null,
      changelog: changelog("1.8.1"),
      tagExists: true,
    }),
    /does not match Android version/
  );
});
