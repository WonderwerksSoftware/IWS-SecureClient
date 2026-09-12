import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,symlink,writeFile,rm} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import {readReleaseProvenance,verifyWindowsRelease} from '../release-provenance.mjs';

const sha256=value=>createHash('sha256').update(value).digest('hex');

test('release provenance rejects tag-only and stale-commit snapshots',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'iws-release-proof-'));
 try {
  await writeFile(path.join(root,'client-version.json'),JSON.stringify({schemaVersion:2,release:'1.0.0',releaseTag:'secure-client-v1.0.0'}));
  await assert.rejects(readReleaseProvenance(root,'secure-client-v1.0.0','a'.repeat(40)));
  await writeFile(path.join(root,'release-provenance.json'),JSON.stringify({releaseTag:'secure-client-v1.0.0',sourceCommit:'a'.repeat(40),tagObject:'b'.repeat(40)}));
  assert.equal((await readReleaseProvenance(root,'secure-client-v1.0.0','a'.repeat(40))).sourceCommit,'a'.repeat(40));
  await assert.rejects(readReleaseProvenance(root,'secure-client-v1.0.0','c'.repeat(40)));
 } finally {await rm(root,{recursive:true,force:true});}
});

test('release provenance rejects legacy and malformed schemas when a source commit is pinned',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'iws-release-schema-'));
 try {
  await writeFile(path.join(root,'client-version.json'),JSON.stringify({schemaVersion:1,releaseTag:'secure-client-v1.0.0'}));
  assert.equal(await readReleaseProvenance(root,'secure-client-v1.0.0'),null);
  await assert.rejects(readReleaseProvenance(root,'secure-client-v1.0.0','a'.repeat(40)),/RELEASE_PROVENANCE_INVALID/);
  await writeFile(path.join(root,'client-version.json'),JSON.stringify({schemaVersion:'2',releaseTag:'secure-client-v1.0.0'}));
  await writeFile(path.join(root,'release-provenance.json'),JSON.stringify({
   releaseTag:'secure-client-v1.0.0',sourceCommit:'a'.repeat(40),tagObject:'b'.repeat(40)
  }));
  await assert.rejects(readReleaseProvenance(root,'secure-client-v1.0.0','a'.repeat(40)),/RELEASE_PROVENANCE_INVALID/);
 } finally {await rm(root,{recursive:true,force:true});}
});

async function windowsFixture(root) {
 const payload=path.join(root,'windows-payload');
 const template=path.join(root,'IWS-Setup-Template.exe');
 const member='Install-IwsPrivateTransport.ps1';
 const contents='trusted payload';
 await mkdir(payload);
 await writeFile(path.join(payload,member),contents);
 const manifest=`${sha256(contents)}  ./${member}\n`;
 await writeFile(path.join(payload,'BUNDLE-MANIFEST.sha256'),manifest);
 await writeFile(template,'MZ trusted template');
 const proof={releaseTag:'secure-client-v1.0.0',sourceCommit:'a'.repeat(40)};
 await writeFile(path.join(root,'release-provenance.json'),JSON.stringify({
  ...proof,
  templateSha256:sha256('MZ trusted template'),
  bundleManifestSha256:sha256(manifest)
 }));
 return {payload,template,member,proof};
}

test('Windows release verification rejects an altered manifest member',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'iws-windows-release-member-'));
 try {
  const fixture=await windowsFixture(root);
  await verifyWindowsRelease(root,fixture.template,fixture.proof);
  await writeFile(path.join(fixture.payload,fixture.member),'altered payload');
  await assert.rejects(verifyWindowsRelease(root,fixture.template,fixture.proof),/WINDOWS_RELEASE_INPUT_MISMATCH/);
 } finally {await rm(root,{recursive:true,force:true});}
});

test('Windows release verification rejects unlisted payload files',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'iws-windows-release-extra-'));
 try {
  const fixture=await windowsFixture(root);
  await writeFile(path.join(fixture.payload,'unlisted.exe'),'untrusted payload');
  await assert.rejects(verifyWindowsRelease(root,fixture.template,fixture.proof),/WINDOWS_RELEASE_INPUT_MISMATCH/);
 } finally {await rm(root,{recursive:true,force:true});}
});

test('Windows release verification rejects traversal and symlink manifest members',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'iws-windows-release-path-'));
 try {
  const fixture=await windowsFixture(root);
  const outside=path.join(root,'outside.exe');
  await writeFile(outside,'outside payload');
  const traversalManifest=`${sha256('outside payload')}  ../outside.exe\n`;
  await writeFile(path.join(fixture.payload,'BUNDLE-MANIFEST.sha256'),traversalManifest);
  await writeFile(path.join(root,'release-provenance.json'),JSON.stringify({
   ...fixture.proof,
   templateSha256:sha256('MZ trusted template'),
   bundleManifestSha256:sha256(traversalManifest)
  }));
  await assert.rejects(verifyWindowsRelease(root,fixture.template,fixture.proof),/WINDOWS_RELEASE_INPUT_MISMATCH/);

  const symlinkManifest=`${sha256('outside payload')}  linked.exe\n`;
  await rm(path.join(fixture.payload,fixture.member));
  await symlink(outside,path.join(fixture.payload,'linked.exe'));
  await writeFile(path.join(fixture.payload,'BUNDLE-MANIFEST.sha256'),symlinkManifest);
  await writeFile(path.join(root,'release-provenance.json'),JSON.stringify({
   ...fixture.proof,
   templateSha256:sha256('MZ trusted template'),
   bundleManifestSha256:sha256(symlinkManifest)
  }));
  await assert.rejects(verifyWindowsRelease(root,fixture.template,fixture.proof),/WINDOWS_RELEASE_INPUT_MISMATCH/);
 } finally {await rm(root,{recursive:true,force:true});}
});
