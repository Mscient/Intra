// Separate-JVM offline acceptance. Generated prices, costs and signing keys are synthetic only.
import {spawn} from 'node:child_process';
import {promises as fs} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const project=path.dirname(fileURLToPath(import.meta.url));
const args=process.argv.slice(2);
const option=name=>{const i=args.indexOf(name);return i<0?undefined:args[i+1];};
const build=path.resolve(option('--build-root')??path.join(project,'build'));
const output=path.resolve(option('--output-root')??path.join(project,'build','research-drill-'+Date.now()));
const javaHome=option('--java-home')??process.env.JAVA_HOME;
if(!javaHome)throw new Error('JDK 21 java-home required');
const java=path.join(javaHome,'bin',process.platform==='win32'?'java.exe':'java');
let jar=path.join(build,'intra-paper-system.jar');try{await fs.access(jar);}catch{jar=path.join(build,'candidate.jar');}
const classpath=jar+path.delimiter+path.join(build,'test-classes');
const workflow='com.mscient.intrapaper.evaluation.portfolio.NiftyResearchWorkflow';
const fixture='com.mscient.intrapaper.evaluation.portfolio.NiftyResearchWorkflowTest';
const sha=b=>createHash('sha256').update(b).digest('hex');
await fs.mkdir(output,{recursive:false});await fs.mkdir(path.join(output,'logs'));
const inputs=path.join(output,'inputs'),workspace=path.join(output,'workspace'),keys=path.join(output,'synthetic-keys');
const records=[];
async function run(name,cls,parameters,expected=0){
 const command=[java,'-cp',classpath,cls,...parameters];
 const r=await new Promise((resolve,reject)=>{let stdout='',stderr='';const child=spawn(java,command.slice(1),{cwd:project,windowsHide:true});child.stdout.on('data',b=>stdout+=b);child.stderr.on('data',b=>stderr+=b);child.on('error',reject);child.on('close',code=>resolve({code,stdout,stderr}));});
 await fs.writeFile(path.join(output,'logs',name+'.log'),r.stdout+r.stderr+'\nEXIT_CODE='+r.code+'\n');
 records.push({name,command,expected_exit_code:expected,exit_code:r.code,stdout_sha256:sha(r.stdout),stderr_sha256:sha(r.stderr)});
 console.log(name+': exit '+r.code+' (expected '+expected+')');
 if(r.code!==expected)throw new Error(name+' failed: '+r.stderr);return r.stdout;
}
const gateArgs=[path.join(keys,'custodian-public.der'),path.join(keys,'journal-public.der'),path.join(keys,'journal-private.der')];
let status='FAIL';
try{
 await run('fixture',fixture,[project,inputs,'fixture']);await run('synthetic-keys',fixture,['synthetic-keys',keys]);
 const basePlan=JSON.parse(await fs.readFile(path.join(inputs,'sma-a.json'),'utf8'));
 const invalidPlan={...basePlan,walk_forward_training_sessions:'0'};
 await fs.writeFile(path.join(inputs,'invalid-window.json'),JSON.stringify(invalidPlan,null,2));
 await run('invalid-window-denied',workflow,['register',path.join(output,'invalid-window'),path.join(inputs,'invalid-window.json')],1);
 const insufficient=path.join(output,'insufficient-window');
 for(const id of ['sma-a','sma-b']){
  const plan=JSON.parse(await fs.readFile(path.join(inputs,id+'.json'),'utf8'));plan.walk_forward_training_sessions='99';
  const planPath=path.join(inputs,'insufficient-'+id+'.json');await fs.writeFile(planPath,JSON.stringify(plan,null,2));
  await run('insufficient-register-'+id,workflow,['register',insufficient,planPath]);
  await run('insufficient-evaluate-'+id,workflow,['evaluate',insufficient,id]);
 }
 const insufficientText=await run('insufficient-report',workflow,['report',insufficient,path.join(output,'insufficient-reports')]);
 const insufficientReport=insufficientText.trim().slice('REPORT='.length);
 await run('insufficient-verify',workflow,['verify-report',insufficientReport]);
 const insufficientResult=JSON.parse(await fs.readFile(path.join(insufficientReport,'report.json'),'utf8'));
 if(insufficientResult.walk_forward.windows.length!==0||!insufficientResult.walk_forward.limitation.includes('Insufficient'))throw new Error('Insufficient windows were not reported honestly');
 for(const id of ['sma-a','sma-b','empty'])await run('register-'+id,workflow,['register',workspace,path.join(inputs,id+'.json')]);
 for(const id of ['sma-a','sma-b','empty'])await run('validate-'+id,workflow,['validate',workspace,id]);
 for(const id of ['sma-a','sma-b','empty'])await run('evaluate-'+id,workflow,['evaluate',workspace,id]);
 const text=await run('report',workflow,['report',workspace,path.join(output,'reports')]);
 const report=text.trim().slice('REPORT='.length);
 await run('verify-report',workflow,['verify-report',report]);
 for(const id of ['sma-a','sma-b','empty'])await run('replay-verify-'+id,workflow,['replay-verify',workspace,id]);
 await run('freeze',workflow,['freeze',workspace,report,...gateArgs]);
 await run('early-holdout-denied',workflow,['holdout',workspace,'early',...gateArgs],1);
 await run('synthetic-sign-authorization',fixture,['synthetic-authorize',workspace,keys]);
 await run('authorize',workflow,['authorize',workspace,path.join(keys,'authorization.token'),...gateArgs]);
 await run('holdout',workflow,['holdout',workspace,'holdout-evaluation-1',...gateArgs]);
 const delivery=await run('read-holdout',workflow,['read-holdout',workspace,'delivery-1',...gateArgs]);
 await run('verify-holdout',workflow,['verify-holdout',workspace,'verify-1',...gateArgs]);
 await run('repeated-holdout-denied',workflow,['holdout',workspace,'holdout-evaluation-2',...gateArgs],1);
 await run('post-holdout-evaluation-denied',workflow,['evaluate',workspace,'sma-a'],1);
 await run('post-holdout-registration-denied',workflow,['register',workspace,path.join(inputs,'sma-b.json')],1);
 const again=await run('read-holdout-again',workflow,['read-holdout',workspace,'delivery-2',...gateArgs]);
 if(delivery!==again)throw new Error('Result delivery changed');
 await run('verify-report-after-holdout',workflow,['verify-report',report]);
 const result=JSON.parse(await fs.readFile(path.join(report,'report.json'),'utf8'));
 for(const window of result.walk_forward.windows){
  if(window.validation_sessions.at(-1)>=window.evaluation_sessions[0]||window.training_sessions.at(-1)>=window.validation_sessions[0])throw new Error('Walk-forward boundary leakage');
 }
 const held=JSON.parse(delivery);
 if(result.registered_trial_count!==3||result.result_trial_count!==3||result.holdout_used_for_selection!==false||held.performance_claim_eligible!==false)throw new Error('Evidence fields or complete trial counts differ');
 status='PASS';
}finally{
 const summary={schema:'INTRA_SYNTHETIC_NIFTY_RESEARCH_DRILL_V1',classification:'SYNTHETIC_PLUMBING_ONLY',performance_claim_eligible:false,status,
  jar_sha256:sha(await fs.readFile(jar)),command_count:records.length,successful_commands:records.filter(r=>r.exit_code===0).length,
  expected_denials:records.filter(r=>r.expected_exit_code===1&&r.exit_code===1).length,records};
 await fs.writeFile(path.join(output,'summary.json'),JSON.stringify(summary,null,2)+'\n');console.log('Overall: '+status+'; commands='+records.length);
}
