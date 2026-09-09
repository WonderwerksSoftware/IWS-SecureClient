import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const read = path => readFile(new URL(`../../${path}`, import.meta.url), "utf8");

test("device builds explicitly select the signed release artifact", async () => {
  const device = await read("scripts/build-android-device.sh");
  assert.match(device, /IWS_ANDROID_VARIANT=release/);
  assert.match(device, /iws-connect-production-rc1\.apk/);
});

test("standalone builds retain debug by default while supporting release", async () => {
  const builder = await read("scripts/build-android-poc.sh");
  assert.match(builder, /IWS_ANDROID_VARIANT:-debug/);
  assert.match(builder, /assembleDebug/);
  assert.match(builder, /assembleRelease/);
  assert.match(builder, /outputs\/apk\/release\/app-release\.apk/);
});

test("release is non-debuggable, unoptimized, and requires the explicit signer", async () => {
  const gradle = await read("android/app/build.gradle");
  assert.match(gradle, /release\s*\{[\s\S]*?debuggable false/);
  assert.match(gradle, /release\s*\{[\s\S]*?minifyEnabled false/);
  assert.match(gradle, /release\s*\{[\s\S]*?shrinkResources false/);
  assert.match(gradle, /release\s*\{[\s\S]*?signingConfig signingConfigs\.iwsDevice/);
  assert.match(gradle, /IWS release builds require explicit signer properties/);
  assert.doesNotMatch(gradle, /signingConfigs\.debug/);
});

test("candidate note records endpoint, CA fingerprint, and unpassed gates", async () => {
  const note = await read("PRODUCTION-CANDIDATE.md");
  assert.match(note, /https:\/\/portal\.iws\.internal\//);
  assert.match(note, /3976d486cf804696206b98fccb56c2315f29a6690a0272891c562fac2b48a781/);
  assert.match(note, /not (?:a )?PASS/i);
  assert.match(note, /signed APK build/i);
  assert.match(note, /install/i);
  assert.match(note, /live/i);
});
