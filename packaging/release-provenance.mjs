import {readFile,lstat,readdir} from 'node:fs/promises';
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
 if(version.schemaVersion===1) {
  if(commit!==undefined && commit!==null) throw new Error('RELEASE_PROVENANCE_INVALID');
  return null;
 }
 if(version.schemaVersion!==2) throw new Error('RELEASE_PROVENANCE_INVALID');
 const proof=await json(path.join(root,'release-provenance.json'));
 if(version.releaseTag!==ref || proof.releaseTag!==ref ||
    !/^[0-9a-f]{40}$/.test(commit??'') || proof.sourceCommit!==commit ||
    !/^[0-9a-f]{40}$/.test(proof.tagObject??'')) throw new Error('RELEASE_PROVENANCE_INVALID');
 return {release:version.release,releaseTag:ref,sourceCommit:commit,tagObject:proof.tagObject};
}

async function payloadFiles(root,current=root) {
 const info=await lstat(current);
 if(info.isSymbolicLink() || !info.isDirectory()) throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
 const files=[];
 for(const entry of await readdir(current,{withFileTypes:true})) {
  const full=path.join(current,entry.name);
  if(entry.isSymbolicLink()) throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
  if(entry.isDirectory()) files.push(...await payloadFiles(root,full));
  else if(entry.isFile()) files.push(path.relative(root,full).split(path.sep).join('/'));
  else throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
 }
 return files;
}

function manifestMember(value) {
 const member=value.startsWith('./') ? value.slice(2) : value;
 if(!member || path.posix.isAbsolute(member) || member.includes('\\') ||
    member.split('/').some(part=>!part || part==='.' || part==='..')) {
  throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
 }
 return member;
}

export async function verifyWindowsRelease(root,template,proof) {
 if(!proof)return;
 try {
  const input=await json(path.join(root,'release-provenance.json'));
  const digest=async file=>createHash('sha256').update(await readFile(file)).digest('hex');
  const payload=path.join(root,'windows-payload');
  const manifestFile=path.join(payload,'BUNDLE-MANIFEST.sha256');
  if(input.sourceCommit!==proof.sourceCommit || input.releaseTag!==proof.releaseTag ||
     input.templateSha256!==await digest(template) ||
     input.bundleManifestSha256!==await digest(manifestFile)) {
   throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
  }

  const listed=new Map();
  const manifest=await readFile(manifestFile,'utf8');
  const lines=manifest.endsWith('\n') ? manifest.slice(0,-1).split('\n') : manifest.split('\n');
  if(lines.length===0 || lines.some(line=>line==='')) throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
  for(const line of lines) {
   const match=/^([0-9a-f]{64})  (.+)$/.exec(line);
   if(!match) throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
   const member=manifestMember(match[2]);
   if(member==='BUNDLE-MANIFEST.sha256' || listed.has(member)) throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
   listed.set(member,match[1]);
  }

  const files=await payloadFiles(payload);
  const actual=files.filter(file=>file!=='BUNDLE-MANIFEST.sha256');
  if(files.filter(file=>file==='BUNDLE-MANIFEST.sha256').length!==1 ||
     actual.length!==listed.size || actual.some(file=>!listed.has(file))) {
   throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
  }
  for(const [member,expected] of listed) {
   if(await digest(path.join(payload,...member.split('/')))!==expected) {
    throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
   }
  }
 } catch {
  throw new Error('WINDOWS_RELEASE_INPUT_MISMATCH');
 }
}
