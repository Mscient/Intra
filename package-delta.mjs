// Builds a review patch against the preserved dirty baseline; never writes to the original checkout.
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
const run = promisify(execFile), work = process.cwd(), original = path.dirname(work);
const preservation = path.join(original,'verification-20261114-preserved');
const output = path.join(work,process.argv[2] ?? 'review-package');
const files = [
  '.github/workflows/java21-build.yml', 'README.md',
  'Intra-Paper-System/README.md', 'Intra-Paper-System/IMPLEMENTATION-TRACKER.md',
  'Intra-Paper-System/docs/OPERATING-GUIDE.md', 'Intra-Paper-System/docs/WINDOWS-JAVA21-CI.md', 'Intra-Paper-System/docs/OFFLINE-VERIFICATION.md',
  'Intra-Paper-System/docs/OFFLINE-CORRECTNESS-2026-11-14.md', 'Intra-Paper-System/test-offline.mjs',
  ...['cli/IntraPaperCli','data/ValidatedDataset','evaluation/EvaluationPartitionReport',
      'marketdata/QuoteJournalReader','marketdata/QuoteJournalWriter', 'simulation/BacktestEngine',
      'simulation/RunIdentity','simulation/RunArtifactWriter','simulation/BacktestCheckpoint',
      'simulation/BacktestCheckpointStore'].map(f => 'Intra-Paper-System/src/main/java/com/mscient/intrapaper/' + f + '.java'),
  ...['OfflineCorePlumbingTest','SingleContractAdmissionTest'].map(f => 'Intra-Paper-System/src/test/java/com/mscient/intrapaper/simulation/' + f + '.java'),
  ...['IMPLEMENTATION-TRACKER','NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN','PAPER-TRADING-BACKTESTING-ROADMAP',
      'ISOLATED-TESTING-PLAN'].map(f => 'research/systems/' + f + '.md'),
];
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
await fs.mkdir(output,{recursive:false});
const inventory = JSON.parse(await fs.readFile(path.join(preservation,'inventory.json'),'utf8'));
const owned = new Set(files), unchanged = [], changedOutsideScope = [];
for (const entry of inventory) {
  if (owned.has(entry.path)) continue;
  if (sha(await fs.readFile(path.join(work,entry.path))).toUpperCase() === entry.sha256) unchanged.push(entry.path);
  else changedOutsideScope.push(entry.path);
}
if (changedOutsideScope.length) throw new Error('Unexpected changes outside this increment: ' + changedOutsideScope.join(', '));
let patch = ''; const changes = [];
for (const file of files) {
  let before, newFile = false;
  try { before = await fs.readFile(path.join(preservation,'files',file)); }
  catch {
    try { before = (await run('git',['show','HEAD:' + file],{encoding:'buffer'})).stdout; }
    catch { before = Buffer.alloc(0); newFile = true; }
  }
  const after = await fs.readFile(path.join(work,file));
  const normalize = bytes => bytes.toString('utf8').replaceAll('\r\n','\n');
  if (normalize(before) === normalize(after)) continue;
  const beforeFile = path.join(output,'before',file), afterFile = path.join(output,'after',file);
  await fs.mkdir(path.dirname(beforeFile),{recursive:true}); await fs.mkdir(path.dirname(afterFile),{recursive:true});
  await fs.writeFile(beforeFile,normalize(before)); await fs.writeFile(afterFile,normalize(after));
  let diff;
  try { diff = (await run('git',['diff','--no-index','--text','--',beforeFile,afterFile],{maxBuffer:2**22})).stdout; }
  catch (error) { if (error.code !== 1) throw error; diff = error.stdout; }
  const hunk = diff.slice(diff.indexOf('@@'));
  patch += 'diff --git a/' + file + ' b/' + file + '\n' + (newFile ? 'new file mode 100644\n' : '')
    + '--- ' + (newFile ? '/dev/null' : 'a/' + file) + '\n+++ b/' + file + '\n' + hunk;
  changes.push({path:file,new_file:newFile,before_sha256:sha(before),after_sha256:sha(after)});
}
await fs.writeFile(path.join(output,'correctness-increment.patch'),patch);
const tested = JSON.parse(await fs.readFile(path.join(work,'vfinal','logs','summary.json'),'utf8'));
const changedAfterTests = [];
for (const [file,hash] of Object.entries(tested.source_sha256)) {
  if (sha(await fs.readFile(path.join(work,'Intra-Paper-System',file))) !== hash) changedAfterTests.push(file);
}
if (changedAfterTests.length) throw new Error('Sources changed after full verification: ' + changedAfterTests.join(', '));
await fs.writeFile(path.join(output,'preservation-and-changes.json'),JSON.stringify({
  original_checkout_written:false,preserved_inventory_count:inventory.length,
  untouched_carried_files_verified:unchanged.length,changed_outside_scope:changedOutsideScope,
  tested_sources_still_match:true,changes,patch_sha256:sha(Buffer.from(patch))
},null,2) + '\n');
console.log('Patch includes ' + changes.length + ' files; ' + unchanged.length + ' unrelated carried files remain byte-identical; tested sources still match.');
