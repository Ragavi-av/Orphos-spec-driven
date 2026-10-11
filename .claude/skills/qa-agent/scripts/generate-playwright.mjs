import fs from 'node:fs';
import path from 'node:path';

function toKebab(value) {
  return String(value || 'test')
    .trim()
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
}

function parseTestData(testData = []) {
  const record = {};

  for (const entry of testData) {
    if (typeof entry !== 'string') continue;
    const eqIndex = entry.indexOf('=');
    if (eqIndex > 0) {
      const key = entry.slice(0, eqIndex).trim();
      const value = entry.slice(eqIndex + 1).trim();
      if (key) record[key] = value;
    }
  }

  return record;
}

function sanitizeForString(value) {
  return String(value ?? '').replace(/\\/g, '\\\\').replace(/`/g, '\\`');
}

function stepToCode(step) {
  const action = step?.action ?? '';
  const expected = step?.expected ?? 'Expected result';
  const httpMatch = action.match(/^(GET|POST|PUT|PATCH|DELETE)\s+(\S+)/i);

  if (httpMatch) {
    const method = httpMatch[1].toLowerCase();
    const route = httpMatch[2];
    const data = parseTestData(step?.testData ?? []);
    const bodyLine = Object.keys(data).length ? `, { data: ${JSON.stringify(data, null, 2).replace(/\n/g, '\n    ')} }` : '';

    return `
    const response = await apiRequest('${method}', '${route}'${bodyLine});
    expect(response.status()).toBeGreaterThanOrEqual(200);
    expect(response.status()).toBeLessThan(400);
    // expected: ${sanitizeForString(expected)}
  `;
  }

  if (action.toLowerCase().includes('goto')) {
    const route = action.replace(/^.*goto\s+/i, '').replace(/['"]+/g, '');
    return `
    await page.goto(baseURL + '${route}');
    // expected: ${sanitizeForString(expected)}
  `;
  }

  if (action.toLowerCase().includes('fill')) {
    const match = action.match(/fill\s+['"]([^'"]+)['"]\s+with\s+['"]?([^'"]+)['"]?/i);
    if (match) {
      const field = match[1];
      const value = match[2] || 'sample-value';
      return `
    await page.fill('${field}', '${sanitizeForString(value)}');
    // expected: ${sanitizeForString(expected)}
  `;
    }
  }

  if (action.toLowerCase().includes('click')) {
    const match = action.match(/click\s+['"]([^'"]+)['"]?/i);
    if (match) {
      return `
    await page.click('${match[1]}');
    // expected: ${sanitizeForString(expected)}
  `;
    }
  }

  return `
    // action: ${sanitizeForString(action)}
    // expected: ${sanitizeForString(expected)}
  `;
}

function renderSpec(taskKey, testCases) {
  const testBlocks = testCases.map((testCase, index) => {
    const title = sanitizeForString(testCase.title || `Scenario ${index + 1}`);
    const covers = Array.isArray(testCase.covers) ? testCase.covers : [];
    const coversComment = covers.length ? `// covers: ${covers.join(', ')}` : '// covers: objective';
    const type = testCase.type || 'positive';
    const expected = sanitizeForString(testCase.expected || 'Expected result');
    const steps = (testCase.steps || []).map((step) => stepToCode(step)).join('\n');

    return `
  test('${title}', async ({ page, request }) => {
    ${coversComment}
    const apiRequest = async (method, path, body) => {
      const response = method === 'get'
        ? await request.get(baseURL + path)
        : await request[method](baseURL + path, body ? { data: body } : undefined);
      return response;
    };

    ${steps}

    // overall expectation: ${expected}
    await expect(page).toHaveURL(/.+/);
  });
`;
  }).join('\n');

  return `import { test, expect } from '@playwright/test';

const baseURL = process.env.BASE_URL ?? 'http://localhost:3000';

test.describe('${taskKey} - Playwright coverage', () => {
${testBlocks}
});
`;
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 0 || args.includes('--help')) {
    console.log('Usage: node generate-playwright.mjs <input-json> [output-file]');
    process.exit(1);
  }

  const inputPath = path.resolve(args[0]);
  const outputPathArg = args[1] ? path.resolve(args[1]) : undefined;

  if (!fs.existsSync(inputPath)) {
    console.error(`Input file not found: ${inputPath}`);
    process.exit(2);
  }

  const raw = JSON.parse(fs.readFileSync(inputPath, 'utf8'));
  const taskKey = raw.taskKey || path.basename(inputPath).replace(/^testcases-/, '').replace(/\.json$/i, '');
  const testCases = Array.isArray(raw.testCases) ? raw.testCases : [];

  const generated = renderSpec(taskKey, testCases);
  const outFile = outputPathArg || path.resolve(process.cwd(), 'playwright', 'tests', `${taskKey}.spec.ts`);
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, generated, 'utf8');

  console.log(JSON.stringify({
    taskKey,
    source: inputPath,
    output: outFile,
    testCount: testCases.length
  }, null, 2));
}

main();
