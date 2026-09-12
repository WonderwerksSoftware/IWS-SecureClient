import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
test('release provenance rejects tag-only and stale-commit snapshots',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'iws-release-proof-'));
 try {
  const {readReleaseProvenance}=await import('../release-provenance.mjs');
  await writeFile(path.join(root,'client-version.json'),JSON.stringify({schemaVersion:2,release:'1.0.0',releaseTag:'secure-client-v1.0.0'}));
  await assert.rejects(readReleaseProvenance(root,'secure-client-v1.0.0','a'.repeat(40)));
  await writeFile(path.join(root,'release-provenance.json'),JSON.stringify({releaseTag:'secure-client-v1.0.0',sourceCommit:'a'.repeat(40),tagObject:'b'.repeat(40)}));
  assert.equal((await readReleaseProvenance(root,'secure-client-v1.0.0','a'.repeat(40))).sourceCommit,'a'.repeat(40));
  await assert.rejects(readReleaseProvenance(root,'secure-client-v1.0.0','c'.repeat(40)));
 } finally {await rm(root,{recursive:true,force:true});}
});
