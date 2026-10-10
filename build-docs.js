// Renders mermaid blocks in the AS-IS design doc to PNGs,
// rewrites the markdown with image refs, and emits docx + pdf via pandoc.

const fs = require('fs');
const path = require('path');
const { execSync, spawnSync } = require('child_process');

const ROOT = __dirname;
const SRC  = path.join(ROOT, 'EIP-AS-IS-Design-Document.md');
const TMP  = path.join(ROOT, 'build-docs-tmp');
const IMG  = path.join(TMP,  'diagrams');
const MD   = path.join(TMP,  'EIP-AS-IS-Design-Document.processed.md');
const DOCX = path.join(ROOT, 'EIP-AS-IS-Design-Document.docx');
const PDF  = path.join(ROOT, 'EIP-AS-IS-Design-Document.pdf');

if (!fs.existsSync(TMP)) fs.mkdirSync(TMP, { recursive: true });
if (!fs.existsSync(IMG)) fs.mkdirSync(IMG, { recursive: true });

const md = fs.readFileSync(SRC, 'utf8');

const mermaidRegex = /```mermaid\r?\n([\s\S]*?)\r?\n```/g;
let i = 0;
const blocks = [];
md.replace(mermaidRegex, (_full, body) => {
  blocks.push(body);
  return '';
});

console.log(`Found ${blocks.length} mermaid diagrams.`);

// Mermaid config: wider canvas, white background, larger font
const cfgPath = path.join(TMP, 'mermaid.config.json');
fs.writeFileSync(cfgPath, JSON.stringify({
  theme: 'default',
  themeVariables: { fontSize: '16px' },
  flowchart: { useMaxWidth: false, htmlLabels: true, curve: 'basis' },
  sequence:  { useMaxWidth: false, mirrorActors: false },
  class:     { useMaxWidth: false },
}));

// Puppeteer config to allow sandbox
const ppPath = path.join(TMP, 'puppeteer.json');
fs.writeFileSync(ppPath, JSON.stringify({ args: ['--no-sandbox'] }));

const pngPaths = [];
blocks.forEach((body, idx) => {
  const num = String(idx + 1).padStart(2, '0');
  const mmd = path.join(TMP, `d${num}.mmd`);
  const png = path.join(IMG, `d${num}.png`);
  fs.writeFileSync(mmd, body);
  console.log(`Rendering diagram ${num}...`);
  const r = spawnSync('mmdc.cmd', [
    '-i', mmd,
    '-o', png,
    '-c', cfgPath,
    '-p', ppPath,
    '-b', 'white',
    '-w', '1600',
    '-s', '2',
  ], { stdio: 'inherit', shell: true });
  if (r.status !== 0) {
    console.error(`Failed to render diagram ${num}`);
    process.exit(1);
  }
  pngPaths.push(png);
});

// Replace mermaid blocks with markdown image refs
let processed = md;
i = 0;
processed = processed.replace(mermaidRegex, () => {
  const rel = path.relative(TMP, pngPaths[i]).replace(/\\/g, '/');
  i += 1;
  return `\n![Diagram ${i}](${rel})\n`;
});

fs.writeFileSync(MD, processed);
console.log(`Wrote processed markdown → ${MD}`);

// Build DOCX
console.log('Building DOCX...');
execSync(
  `pandoc "${MD}" -f markdown -t docx -o "${DOCX}" --toc --toc-depth=3 --number-sections=false`,
  { stdio: 'inherit', cwd: TMP }
);
console.log(`Wrote ${DOCX}`);

// Build PDF via xelatex (MiKTeX). Use a Unicode-safe engine.
console.log('Building PDF...');
execSync(
  `pandoc "${MD}" -f markdown -t pdf -o "${PDF}" --pdf-engine=xelatex --toc --toc-depth=3 -V geometry:margin=1in -V mainfont="Calibri" -V monofont="Consolas" -V colorlinks=true`,
  { stdio: 'inherit', cwd: TMP }
);
console.log(`Wrote ${PDF}`);
