// Packaged, synthetic CLI acceptance. Every output is under a fresh caller-selected directory.
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const project = path.dirname(fileURLToPath(import.meta.url));
const [jarArg, outputArg, javaHome] = process.argv.slice(2);
if (!jarArg || !outputArg || !javaHome) throw new Error('Usage: node test-paper-release.mjs <verified.jar> <new-output-dir> <jdk21-home>');
const jar = path.resolve(jarArg), output = path.resolve(outputArg);
await fs.mkdir(output, { recursive: false });
await fs.mkdir(path.join(output, 'temp'));
const java = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const report = { classification: 'SYNTHETIC_PLUMBING_ONLY', performance_claim_eligible: false,
  operating_evidence_eligible: false, jar_sha256: sha(await fs.readFile(jar)), commands: [], checks: 0 };
const bars = path.join(project, 'examples', 'synthetic-futures-bars.csv');
const config = path.join(project, 'examples', 'synthetic-run.properties');
const dir = name => path.join(output, name);
async function cli(expected, ...args) {
  const command = [java, '-Djava.io.tmpdir=' + dir('temp'), '-jar', jar, ...args];
  const result = await new Promise((resolve, reject) => {
    const child = spawn(command[0], command.slice(1), { cwd: project, windowsHide: true,
      env: { ...process.env, INTRA_PAPER_RUNS_DIR: dir('historical'), TEMP: dir('temp'), TMP: dir('temp') } });
    let text = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('CLI timeout: ' + args[0])); }, 60000);
    child.stdout.on('data', b => { text += b; }); child.stderr.on('data', b => { text += b; });
    child.on('error', e => { clearTimeout(timer); reject(e); });
    child.on('close', code => { clearTimeout(timer); resolve({ code, text }); });
  });
  report.commands.push({ command, exit_code: result.code, expected_exit_code: expected, output: result.text });
  console.log(`${args[0]}: exit ${result.code} (expected ${expected})`);
  if (result.code !== expected) throw new Error(result.text);
  return result.text;
}
function check(condition, label) { report.checks++; if (!condition) throw new Error(label); }
try {
  await cli(0, 'validate', bars, config);
  await cli(2, 'paper-verify', bars, config, dir('absent'));
  check(await fs.stat(dir('absent')).then(() => false, () => true), 'Verification created an absent session');
  await cli(0, 'paper-run', bars, config, dir('resumed'), '17');
  await cli(0, 'paper-verify', bars, config, dir('resumed'));
  await cli(0, 'paper-replay-verify', bars, config, dir('resumed'));
  await cli(0, 'paper-run', bars, config, dir('resumed'));
  await cli(0, 'paper-verify', bars, config, dir('resumed'));
  await cli(0, 'paper-replay-verify', bars, config, dir('resumed'));
  await cli(0, 'paper-run', bars, config, dir('full'));
  await cli(0, 'paper-verify', bars, config, dir('full'));
  await cli(0, 'paper-replay-verify', bars, config, dir('full'));
  for (const file of ['input-manifest.json', 'checkpoint.txt', 'paper.journal', 'session-report.txt', 'session-manifest.json'])
    check((await fs.readFile(path.join(dir('resumed'), file))).equals(await fs.readFile(path.join(dir('full'), file))), 'Restart differs: ' + file);
  const manifest = JSON.parse(await fs.readFile(path.join(dir('full'), 'session-manifest.json'), 'utf8'));
  check(manifest.session_complete === true && manifest.performance_claim_eligible === false && manifest.operating_evidence_eligible === false,
    'Complete synthetic session eligibility');
  await cli(0, 'paper-run', bars, config, dir('stopped'), '6');
  await cli(0, 'paper-stop', dir('stopped'));
  await cli(0, 'paper-run', bars, config, dir('stopped'));
  await cli(0, 'paper-verify', bars, config, dir('stopped'));
  await cli(0, 'paper-replay-verify', bars, config, dir('stopped'));
  const stop = await fs.readFile(path.join(dir('stopped'), 'session-report.txt'), 'utf8');
  check(stop.includes('reason=OPERATOR_STOP') && stop.includes('\nfills=0\n') && stop.includes('session_complete=false'), 'Operator stop accounting');
  await cli(0, 'run', bars, config);
  const run = (await fs.readdir(dir('historical'))).find(x => /^[0-9a-f]{24}$/.test(x));
  check(Boolean(run), 'Historical run missing');
  await cli(0, 'verify', path.join(dir('historical'), run));
  await cli(0, 'replay-verify', bars, config, path.join(dir('historical'), run));
  await fs.mkdir(dir('gap'));
  for (const name of ['synthetic-run.properties', 'synthetic-futures-bars.csv', 'synthetic-instrument-master.csv',
    'synthetic-session-calendar.csv', 'synthetic-market-rule-regimes.csv', 'synthetic-cost-schedule.csv'])
    await fs.copyFile(path.join(project, 'examples', name), path.join(dir('gap'), name));
  const gapBars = path.join(dir('gap'), 'synthetic-futures-bars.csv'), gapConfig = path.join(dir('gap'), 'synthetic-run.properties');
  const rows = (await fs.readFile(gapBars, 'utf8')).trimEnd().split(/\r?\n/); rows.splice(3, 1);
  await fs.writeFile(gapBars, rows.join('\n') + '\n');
  await fs.writeFile(gapConfig, (await fs.readFile(gapConfig, 'utf8'))
    .replace('quality.allow_intrasession_gaps=false', 'quality.allow_intrasession_gaps=true')
    .replace('quality.max_missing_intervals_per_session=0', 'quality.max_missing_intervals_per_session=1'));
  await cli(0, 'validate', gapBars, gapConfig);
  await cli(2, 'paper-run', gapBars, gapConfig, dir('gap-session'));
  await cli(2, 'run', gapBars, gapConfig);
  await fs.cp(dir('full'), dir('tampered'), { recursive: true, errorOnExist: true });
  const target = path.join(dir('tampered'), 'session-manifest.json');
  await fs.writeFile(target, (await fs.readFile(target, 'utf8')).replace('"performance_claim_eligible": false', '"performance_claim_eligible": true'));
  await cli(2, 'paper-verify', bars, config, dir('tampered'));
  await cli(2, 'paper-replay-verify', bars, config, dir('tampered'));
  report.status = 'PASS';
} catch (error) { report.status = 'FAIL'; report.error = error.message; process.exitCode = 1; }
finally {
  report.command_count = report.commands.length;
  await fs.writeFile(dir('summary.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(`${report.status}: ${report.command_count} CLI commands, ${report.checks} artifact checks`);
}
