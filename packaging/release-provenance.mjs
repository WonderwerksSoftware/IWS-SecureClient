import {readFile,lstat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';

async function json(file) {
 const info=await lstat(file);
 if(!info.isFile() || info.isSymbolicLink()) throw new Error('RELEASE_PROVENANCE_INVALID');
 return JSON.parse(await readFile(file,'utf8'));
}
export async function readReleaseProvenance(root,ref,commit) {
 let version;
 try {version=await json(path.join(root,'client-version.json'));}
 catch(error) {if(error.code==='ENOENT')return null;throw error;}
 if(version.schemaVersion===1)return null;
 const proof=await json(path.join(root,'release-provenance.json'));
 if(version.schemaVersion!==2 || version.releaseTag!==ref || proof.releaseTag!==ref ||
    !/^[0-9a-f]{40}$/.test(commit??'') || proof.sourceCommit!==commit ||
    !/^[0-9a-f]{40}$/.test(proof.tagObject??'')) throw new Error('RELEASE_PROVENANCE_INVALID');
 return {release:version.release,releaseTag:ref,sourceCommit:commit,tagObject:proof.tagObject};
}
export async function verifyWindowsRelease(root,template,proof) {
 if(!proof)return;
 const input=await json(path.join(root,'release-provenance.json'));
 const digest=async file=>createHash('sha256').update(await readFile(file)).digest('hex');
 if(input.sourceCommit!==proof.sourceCommit || input.releaseTag!==proof.releaseTag ||
    input.templateSha256!==await digest(template) ||
    input.bundleManifestSha256!==await digest(path.join(root,'windows-payload/BUNDLE-MANIFEST.sha256')))
    throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
}
