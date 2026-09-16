#!/usr/bin/env node
import {execFileSync} from 'node:child_process';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import path from 'node:path';
const [tag,output]=process.argv.slice(2);
if(!/^secure-client-v[0-9][A-Za-z0-9.-]*$/.test(tag??'') || !path.isAbsolute(output??''))throw Error('RELEASE_EXPORT_ARGUMENTS_INVALID');
const git=(...args)=>execFileSync('git',args,{encoding:'utf8'}).trim();
if(git('cat-file','-t','refs/tags/'+tag)!=='tag')throw Error('ANNOTATED_RELEASE_TAG_REQUIRED');
const sourceCommit=git('rev-parse',tag+'^{commit}');
const tagObject=git('rev-parse','refs/tags/'+tag);
const version=JSON.parse(git('show',sourceCommit+':client-version.json'));
if(version.schemaVersion!==2 || version.releaseTag!==tag)throw Error('RELEASE_TAG_MANIFEST_MISMATCH');
await mkdir(output,{mode:0o755});
const archive=execFileSync('git',['archive','--format=tar',sourceCommit],{maxBuffer:128*1024*1024});
execFileSync('tar',['-xf','-','-C',output],{input:archive});
await writeFile(path.join(output,'release-provenance.json'),JSON.stringify({releaseTag:tag,sourceCommit,tagObject},null,2)+'\n',{mode:0o644});
console.log(JSON.stringify({releaseTag:tag,sourceCommit,tagObject,output}));
