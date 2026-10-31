import { mkdir, readFile, writeFile, realpath, stat, rename } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { homedir, networkInterfaces } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CodexRpc } from './rpc.mjs';
import { createBridge } from './bridge.mjs';

const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const privateDirectory = path.join(directory, '.private');
await mkdir(privateDirectory, { recursive: true, mode: 0o700 });
const tokenFile = path.join(privateDirectory, 'token');
try { await writeFile(tokenFile, randomBytes(32).toString('base64url'), { flag: 'wx', mode: 0o600 }); }
catch (error) { if (error.code !== 'EEXIST') throw error; }
const token = (await readFile(tokenFile, 'utf8')).trim();
if (token.length < 32) throw new Error('Token harus minimal 32 karakter.');
const stateFile = path.join(privateDirectory, 'state.json');
let state;
try { state = JSON.parse(await readFile(stateFile, 'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw error; state = { owned: [], forks: {} }; }
const root = await realpath(process.env.REMOTE_CODEX_ROOT || path.dirname(directory));
const socket = process.env.REMOTE_CODEX_SOCKET || path.join(process.env.CODEX_HOME || path.join(homedir(), '.codex'), 'app-server-control', 'app-server-control.sock');
const shared = await stat(socket).then(s => s.isSocket()).catch(() => false);
const binary = process.env.CODEX_BIN || 'codex';
const rpc = new CodexRpc(binary, shared ? ['app-server', 'proxy', '--sock', socket] : ['app-server']);
try { await rpc.initialize(); } catch (error) { rpc.close(); throw error; }
let saving = Promise.resolve();
const server = createBridge({ rpc, token, root, shared, state, saveState: async value => {
  const snapshot = JSON.stringify(value);
  saving = saving.catch(() => {}).then(async () => {
    await writeFile(stateFile + '.tmp', snapshot, { mode: 0o600 });
    await rename(stateFile + '.tmp', stateFile);
  });
  await saving;
} });
const host = process.env.REMOTE_CODEX_HOST || '127.0.0.1';
const port = Number(process.env.PORT || 8787);
server.on('error', error => { console.error(error.message); rpc.close(); process.exitCode = 1; });
server.listen(port, host, () => {
  console.log(`Remote Codex: http://${host}:${port}`);
  console.log(`Mode: ${shared ? 'shared (proses Codex yang sama)' : 'standalone (chat desktop disalin saat dilanjutkan)'}`);
  console.log(`Workspace: ${root}`);
  console.log(`Token pairing tersimpan di ${tokenFile}`);
  if (host === '0.0.0.0') {
    for (const addresses of Object.values(networkInterfaces())) for (const a of addresses || []) {
      if (a.family === 'IPv4' && !a.internal) console.log(`Alamat HP: http://${a.address}:${port}`);
    }
  }
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.close(); rpc.close(); });
