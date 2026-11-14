// Dependency-free offline Java 21 build/test/package runner. Fixtures are synthetic plumbing only.
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const project = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
function option(name) { const i = args.indexOf(name); return i < 0 ? undefined : args[i + 1]; }
const root = path.resolve(option('--output-root') ?? path.join(project, 'build', 'offline-' + Date.now()));
const expectedCommit = option('--expected-commit');
const requestedSuite = option('--suite');
const javaHome = option('--java-home') ?? process.env.JAVA_HOME;
const results = {};
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
async function walk(dir) {
  const files = [];
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const name = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...await walk(name)); else files.push(name);
  }
  return files.sort();
}
async function execute(exe, commandArgs, logName, env = {}, cwd = project) {
  return await new Promise((resolve, reject) => {
    let output = '';
    const child = spawn(exe, commandArgs, { cwd, windowsHide: true, env: { ...process.env, ...env } });
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    child.on('error', reject);
    child.on('close', async code => {
      try {
        if (logName) await fs.writeFile(path.join(root, 'logs', logName + '.log'), output + '\nEXIT_CODE=' + code + '\n');
        resolve({ code, output });
      } catch (error) { reject(error); }
    });
  });
}
async function stage(name, exe, commandArgs, env, cwd) {
  console.log('RUN ' + name);
  const run = await execute(exe, commandArgs, name, env, cwd);
  results[name] = run.code === 0 ? 'PASS' : 'FAIL (exit ' + run.code + ')';
  console.log(name + ': ' + results[name]);
  console.log(run.output.trim());
  return run;
}
const suites = [
  ['simulation.SingleContractAdmissionTest', [project, path.join(root,'admission')]],
  ['simulation.OfflineCorePlumbingTest', [project, path.join(root,'core')]],
  ['data.SynchronizedBarFrameBuilderTest', []],
  ['reference.DatedInstrumentMappingRegistryTest', []],
  ['marketdata.InstrumentSequenceAnalyzerTest', []],
  ['marketdata.InstrumentFeedCoverageAnalyzerTest', []],
  ['ops.OperationalKillControllerTest', []],
  ['riskgateway.RiskGatewayDurabilityTest', [path.join(root,'durability')]],
  ['riskgateway.RiskGatewayProcessTest', [path.join(root,'process')]],
  ['simulation.portfolio.PortfolioAccountingLedgerTest', []],
  ['simulation.portfolio.PortfolioReplayEngineTest', []],
  ['simulation.portfolio.PortfolioExecutionCostScheduleTest', []],
  ['simulation.portfolio.PortfolioReplayArtifactVerifierTest', []],
  ['simulation.portfolio.PortfolioSettlementTest', []],
  ['simulation.portfolio.PortfolioSettlementFixtures', [path.join(root,'settlement-fixtures')]],
  ['simulation.portfolio.risk.PortfolioRiskReplayTest', [project]],
  ['audit.portfolio.PortfolioRiskAuditTest', [project]],
  ['audit.portfolio.PortfolioRiskSettlementFixtures', [project]],
  ['audit.portfolio.PortfolioSettlementAuditTest', [project]],
  ['evaluation.portfolio.PortfolioTrialEvaluationTest', [project]],
  ['evaluation.portfolio.PortfolioHoldoutReleaseTest', [project]],
  ['ops.drill.IncidentDrillTest', [path.join(project,'examples','incident-drills'), path.join(root,'incident')]],
  ['ops.evidence.DailyOpsEvidenceCheckerTest', [path.join(project,'examples','daily-ops-evidence'), path.join(root,'daily')]],
  ['permission.preflight.PermissionPreflightTest', [project, path.join(root,'permission')]],
  ['audit.reconciliation.ExternalLedgerAuditTest', [project, path.join(root,'external')]],
  ['pricing.OptionPricingValidation', [path.join(project,'..','research','systems','option-pricing-benchmark','r11_reference_cases.csv')]],
];
const selectedSuites = requestedSuite ? suites.filter(s => s[0] === requestedSuite) : suites;
if (requestedSuite && selectedSuites.length !== 1) throw new Error('Unknown --suite: ' + requestedSuite);
for (const name of ['production-compile','test-compile', ...suites.map(s => s[0]), 'production-package','manifest-check']) results[name] = 'NOT_RUN';
let report = { classification: 'SYNTHETIC_PLUMBING_ONLY', performance_claim_eligible: false,
  coverage: requestedSuite ? 'FOCUSED_ONLY' : 'ALL_JAVA_TEST_ENTRYPOINTS_AND_PRICING_VALIDATION',
  independent_quantlib: 'NOT_RUN: requires an installed, pinned QuantLib 1.44 Python environment; no dependencies downloaded', results };
try {
  // Fresh output avoids mistaking stale logs, classes or JARs for current success.
  await fs.mkdir(path.dirname(root), { recursive: true });
  await fs.mkdir(root, { recursive: false });
  await fs.mkdir(path.join(root,'logs')); await fs.mkdir(path.join(root,'temp'));
  if (!javaHome) throw new Error('Set JAVA_HOME to a JDK 21 installation.');
  const tool = name => path.join(javaHome,'bin',name + (process.platform === 'win32' ? '.exe' : ''));
  const version = await execute(tool('javac'), ['-version'], 'environment');
  if (version.code !== 0 || !/^javac 21(?:\.|\s|$)/m.test(version.output)) throw new Error('JDK 21 required: ' + version.output);
  const git = await execute('git',['rev-parse','HEAD']);
  const status = await execute('git',['status','--porcelain','--untracked-files=normal']);
  const head = git.output.trim();
  const buildCommit = git.code === 0 && /^[0-9a-f]{40}$/.test(head) && status.code === 0 && !status.output.trim() ? head : 'unknown-dirty';
  if (expectedCommit && (head !== expectedCommit || buildCommit !== expectedCommit)) throw new Error('CI requires a clean checkout of the expected commit.');
  report = { ...report, source_commit: head, build_commit: buildCommit, jdk: version.output.trim() };
  const mainSources = (await walk(path.join(project,'src','main','java'))).filter(f => f.endsWith('.java'));
  const testSources = [...await walk(path.join(project,'src','test','java')),
    ...await walk(path.join(project,'validation','java'))].filter(f => f.endsWith('.java'));
  const listed = new Set(suites.map(s => 'com.mscient.intrapaper.' + s[0]));
  const unlisted = [];
  for (const file of testSources) {
    const source = await fs.readFile(file,'utf8');
    if (!/public\s+static\s+void\s+main\s*\(/.test(source)) continue;
    const packageName = source.match(/\bpackage\s+([\w.]+)\s*;/)?.[1];
    if (!listed.has(packageName + '.' + path.basename(file,'.java'))) unlisted.push(file);
  }
  if (unlisted.length) throw new Error('Unexecuted test classes: ' + unlisted.join(', '));
  report.source_sha256 = Object.fromEntries(await Promise.all([...mainSources,...testSources].map(async f => [path.relative(project,f).replaceAll('\\','/'),sha(await fs.readFile(f))])));
  const classes = path.join(root,'classes'), tests = path.join(root,'test-classes');
  await fs.mkdir(classes); await fs.mkdir(tests);
  for (const [name,sources,output,classpath] of [['production-compile',mainSources,classes,null],['test-compile',testSources,tests,classes]]) {
    const argfile = path.join(root,name + '.args');
    await fs.writeFile(argfile,sources.map(f => '"' + f.replaceAll('\\','/') + '"').join('\n') + '\n');
    const run = await stage(name,tool('javac'),['--release','21','-encoding','UTF-8',...(classpath ? ['-cp',classpath] : []),'-d',output,'@' + argfile]);
    if (run.code !== 0) throw new Error(name + ' failed');
  }
  const env = { TEMP: path.join(root,'temp'), TMP: path.join(root,'temp'), INTRA_PAPER_BUILD_DIR: root };
  for (const [name,parameters] of selectedSuites) {
    await stage(name,tool('java'),['-ea','-Djava.io.tmpdir=' + env.TEMP,'-cp',classes + path.delimiter + tests,'com.mscient.intrapaper.' + name,...parameters],env);
  }
  if (selectedSuites.some(s => results[s[0]] !== 'PASS')) throw new Error('Offline suites failed; production packaging skipped.');
  if (requestedSuite) { report.status = 'PASS (FOCUSED_ONLY)'; }
  else {
  const manifest = path.join(root,'MANIFEST.MF');
  await fs.writeFile(manifest,'Manifest-Version: 1.0\r\nMain-Class: com.mscient.intrapaper.cli.IntraPaperCli\r\nBuild-Commit: ' + buildCommit + '\r\nImplementation-Version: 1.0.0\r\n\r\n');
  const jar = path.join(root,'intra-paper-system.jar');
  if ((await stage('production-package',tool('jar'),['--create','--file',jar,'--manifest',manifest,'-C',classes,'.'])).code !== 0) throw new Error('JAR packaging failed');
  // Extract and inspect the actual JAR manifest, not merely the manifest input file.
  const extracted = path.join(root,'manifest-inspection'); await fs.mkdir(extracted);
  const extraction = await stage('manifest-extract',tool('jar'),['--extract','--file',jar,'META-INF/MANIFEST.MF'],{},extracted);
  if (extraction.code !== 0) throw new Error('Cannot inspect actual JAR manifest');
  const actual = (await fs.readFile(path.join(extracted,'META-INF','MANIFEST.MF'),'utf8')).replace(/\r?\n /g,'');
  const fields = [...actual.matchAll(/^Build-Commit: ([^\r\n]+)/gm)];
  if (fields.length !== 1 || fields[0][1] !== buildCommit) throw new Error('Packaged build identity mismatch');
  results['manifest-check'] = 'PASS'; report.jar_sha256 = sha(await fs.readFile(jar)); report.status = 'PASS';
  }
} catch (error) {
  report.status = 'FAIL'; report.error = error.message; process.exitCode = 1; console.error(error.message);
} finally {
  console.log('Overall: ' + report.status);
  // Never overwrite results in a pre-existing output root.
  if (report.source_commit) await fs.writeFile(path.join(root,'logs','summary.json'),JSON.stringify(report,null,2) + '\n');
}
