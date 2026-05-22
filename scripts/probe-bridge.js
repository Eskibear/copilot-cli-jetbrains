#!/usr/bin/env node
// Smoke test for the IDE bridge. Reads the JetBrains lock file from
// ~/.copilot/ide and runs two probes against it:
//
//   - raw `net.connect` POST (no HTTP client library)
//   - `undici.request` POST (same library the Copilot CLI's MCP transport uses)
//
// Usage:
//   node scripts/probe-bridge.js
//
// Exit code 0 if both probes succeed, 1 otherwise.

const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');

function lockDir() {
  const xdg = process.env.XDG_STATE_HOME;
  return path.join(xdg || os.homedir(), '.copilot', 'ide');
}

function findLockFile() {
  const dir = lockDir();
  if (!fs.existsSync(dir)) throw new Error(`No lock dir: ${dir}`);
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.lock'));
  const candidates = [];
  for (const f of files) {
    try {
      const info = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      candidates.push({ file: f, info });
    } catch (_) { /* skip */ }
  }
  if (candidates.length === 0) throw new Error(`No lock files in ${dir}`);
  // Prefer JetBrains entries.
  const jb = candidates.find((c) => /idea|intellij|jetbrains|webstorm|pycharm|goland|rubymine|phpstorm|rider|clion|datagrip|fleet/i.test(c.info.ideName || ''));
  return jb || candidates[0];
}

function initRequestBody() {
  return JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'probe-bridge', version: '0.0.1' },
    },
  });
}

// ---------- Raw net.connect probe ----------

function buildHttpRequest(method, body, headers) {
  const lines = [];
  lines.push(`${method} /mcp HTTP/1.1`);
  lines.push('Host: localhost');
  lines.push('Connection: close');
  for (const [k, v] of Object.entries(headers || {})) lines.push(`${k}: ${v}`);
  if (body != null) {
    lines.push('Content-Type: application/json');
    lines.push(`Content-Length: ${Buffer.byteLength(body)}`);
  }
  lines.push('');
  lines.push(body || '');
  return lines.join('\r\n');
}

function parseHttpResponse(raw) {
  const idx = raw.indexOf('\r\n\r\n');
  if (idx < 0) throw new Error('No header/body delimiter found');
  const headerBlock = raw.slice(0, idx).toString('utf8');
  const body = raw.slice(idx + 4);
  const headerLines = headerBlock.split('\r\n');
  const [proto, status, ...reasonParts] = headerLines[0].split(' ');
  const headers = {};
  for (const h of headerLines.slice(1)) {
    const i = h.indexOf(':');
    if (i > 0) headers[h.slice(0, i).trim().toLowerCase()] = h.slice(i + 1).trim();
  }
  return { proto, status: parseInt(status, 10), reason: reasonParts.join(' '), headers, body };
}

function rawSend(socketPath, payload) {
  return new Promise((resolve, reject) => {
    const client = net.connect({ path: socketPath }, () => client.write(payload));
    const chunks = [];
    client.on('data', (c) => chunks.push(c));
    client.on('end', () => resolve(Buffer.concat(chunks)));
    client.on('error', reject);
    client.setTimeout(5000, () => { reject(new Error('timeout')); client.destroy(); });
  });
}

async function rawProbe(info) {
  console.log('--- Probe 1: raw net.connect ---');
  const req = buildHttpRequest('POST', initRequestBody(), info.headers || {});
  const raw = await rawSend(info.socketPath, req);
  const res = parseHttpResponse(raw);
  console.log(`HTTP ${res.status} ${res.reason}`);
  console.log(`  mcp-session-id: ${res.headers['mcp-session-id'] || '(missing)'}`);
  console.log(`  body: ${res.body.toString('utf8').slice(0, 200)}`);
  if (res.status !== 200) throw new Error(`Non-200: ${res.status}`);
  const parsed = JSON.parse(res.body.toString('utf8'));
  if (!parsed.result || !parsed.result.protocolVersion) throw new Error('Missing result.protocolVersion');
  console.log(`  protocolVersion negotiated: ${parsed.result.protocolVersion}`);
  console.log('  OK');
}

// ---------- undici/fetch probe ----------

async function undiciProbe(info) {
  console.log('--- Probe 2: undici.request (same lib as CLI) ---');
  let undici;
  try {
    undici = require('undici');
  } catch (e) {
    console.log('  undici not available; skipping. (npm install -g undici if you want this probe.)');
    return;
  }
  const dispatcher = new undici.Agent({
    connect: { socketPath: info.socketPath },
  });
  const res = await undici.request('http://localhost/mcp', {
    method: 'POST',
    headers: {
      ...(info.headers || {}),
      'content-type': 'application/json',
      'accept': 'application/json, text/event-stream',
    },
    body: initRequestBody(),
    dispatcher,
  });
  console.log(`HTTP ${res.statusCode}`);
  console.log(`  mcp-session-id: ${res.headers['mcp-session-id'] || '(missing)'}`);
  const bodyText = await res.body.text();
  console.log(`  body: ${bodyText.slice(0, 200)}`);
  if (res.statusCode !== 200) throw new Error(`Non-200: ${res.statusCode}`);
  const parsed = JSON.parse(bodyText);
  if (!parsed.result || !parsed.result.protocolVersion) throw new Error('Missing result.protocolVersion');
  console.log(`  protocolVersion negotiated: ${parsed.result.protocolVersion}`);
  await dispatcher.close();
  console.log('  OK');
}

// ---------- main ----------

async function main() {
  const { file, info } = findLockFile();
  console.log(`Lock file:   ${file}`);
  console.log(`IDE:         ${info.ideName} (pid=${info.pid}, scheme=${info.scheme})`);
  console.log(`Socket path: ${info.socketPath}`);
  console.log(`Workspaces:  ${(info.workspaceFolders || []).join(', ')}`);
  console.log('');

  let ok = true;
  try { await rawProbe(info); } catch (e) { console.error(`  FAIL: ${e.message}`); ok = false; }
  console.log('');
  try { await undiciProbe(info); } catch (e) { console.error(`  FAIL: ${e.message}`); ok = false; }
  console.log('');
  if (!ok) process.exit(1);
  console.log('All probes passed.');
}

main().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
