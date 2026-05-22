#!/usr/bin/env node
// Smoke test for the IDE bridge. Reads the JetBrains lock file from
// ~/.copilot/ide and sends an MCP `initialize` over the named pipe / socket.
//
// Usage:
//   node scripts/probe-bridge.js
//
// Prints the lock file picked, the bytes sent, and the parsed JSON-RPC response.
// Exit code 0 on success, 1 on any failure.

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
  if (idx < 0) throw new Error('No header/body delimiter found in response');
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

function send(socketPath, payload) {
  return new Promise((resolve, reject) => {
    const client = net.connect({ path: socketPath }, () => {
      client.write(payload);
    });
    const chunks = [];
    client.on('data', (c) => chunks.push(c));
    client.on('end', () => resolve(Buffer.concat(chunks)));
    client.on('error', reject);
    client.setTimeout(5000, () => reject(new Error('timeout')));
  });
}

async function main() {
  const { file, info } = findLockFile();
  console.log(`Lock file:    ${file}`);
  console.log(`IDE:          ${info.ideName} (pid=${info.pid}, scheme=${info.scheme})`);
  console.log(`Socket path:  ${info.socketPath}`);
  console.log(`Workspaces:   ${(info.workspaceFolders || []).join(', ')}`);

  const initBody = JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'probe-bridge', version: '0.0.1' },
    },
  });
  const req = buildHttpRequest('POST', initBody, info.headers || {});
  console.log('---\nSending initialize...');
  const raw = await send(info.socketPath, req);
  const res = parseHttpResponse(raw);
  console.log(`HTTP ${res.status} ${res.reason}`);
  console.log(`mcp-session-id: ${res.headers['mcp-session-id'] || '(missing)'}`);
  console.log('Body:');
  console.log(res.body.toString('utf8'));

  if (res.status !== 200) process.exit(1);
  const parsed = JSON.parse(res.body.toString('utf8'));
  if (!parsed.result || !parsed.result.protocolVersion) {
    console.error('Missing result.protocolVersion');
    process.exit(1);
  }
  console.log('OK');
}

main().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
