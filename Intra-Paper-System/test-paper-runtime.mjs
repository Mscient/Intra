// Focused dependency-free Java 21 forward-paper drill. Local inputs only.
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const project = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(process.argv[2] ?? path.join(project, 'build', 'paper-' + Date.now()));
const java = process.env.JAVA_HOME;
if (!java) throw new Error('JAVA_HOME must point to JDK 21');
await fs.mkdir(output, { recursive: false });
const classes = path.join(output, 'classes'); await fs.mkdir(classes);
async function walk(dir) {
  const result = [];
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) result.push(...await walk(p)); else if (p.endsWith('.java')) result.push(p);
  }
  return result.sort();
}
async function run(name, tool, args) {
  const child = spawn(path.join(java, 'bin', tool + (process.platform === 'win32' ? '.exe' : '')), args,
    { cwd: project, windowsHide: true });
  let text = '';
  child.stdout.on('data', b => { text += b; process.stdout.write(b); });
  child.stderr.on('data', b => { text += b; process.stderr.write(b); });
  const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
  await fs.writeFile(path.join(output, name + '.log'), text + '\nEXIT_CODE=' + code + '\n');
  if (code !== 0 || text.includes('An exception has occurred in the compiler')) throw new Error(name + ' failed: ' + code);
}
const sources = [...await walk(path.join(project, 'src/main/java')), ...await walk(path.join(project, 'src/test/java/com/mscient/intrapaper/paper'))];
const argfile = path.join(output, 'sources.args');
await fs.writeFile(argfile, sources.map(p => '"' + p.replaceAll('\\', '/') + '"').join('\n'));
await run('compile', 'javac', ['--release', '21', '-encoding', 'UTF-8', '-d', classes, '@' + argfile]);
await run('paper-drill', 'java', ['-ea', '-cp', classes, 'com.mscient.intrapaper.paper.PaperRuntimeTest', project, path.join(output, 'drills')]);
console.log('PASS: PAPER_PLUMBING_ONLY; external_connection=false; G0=OPEN');
