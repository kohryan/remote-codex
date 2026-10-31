import { createServer } from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { realpath, readdir } from 'node:fs/promises';
import path from 'node:path';

export function authorized(header, token) {
  if (typeof header !== 'string') return false;
  const hash = value => createHash('sha256').update(value).digest();
  return timingSafeEqual(hash(header), hash(`Bearer ${token}`));
}
export function inside(root, directory) {
  const relative = path.relative(root, directory);
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}
function fail(status, message) { throw Object.assign(new Error(message), { status }); }
function text(value, max = 32000) {
  if (typeof value !== 'string' || !value.trim() || value.length > max) fail(400, 'Teks kosong atau terlalu panjang.');
  return value.trim();
}
async function body(request) {
  let raw = '';
  for await (const chunk of request) {
    raw += chunk;
    if (Buffer.byteLength(raw) > 65536) fail(413, 'Pesan terlalu besar.');
  }
  try {
    const parsed = JSON.parse(raw || '{}');
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) fail(400, 'JSON harus berupa object.');
    return parsed;
  } catch (error) { fail(error.status || 400, 'JSON tidak valid.'); }
}
export function createBridge({ rpc, token, root, shared = false, state, saveState = async () => {} }) {
  const live = new Map(), approvals = new Map(), loaded = new Set(), locks = new Set();
  const sent = new Map();
  const policy = { sandbox: 'workspace-write', approvalPolicy: 'on-request', approvalsReviewer: 'user' };
  function cache(id) {
    if (!live.has(id)) live.set(id, { items: new Map(), turn: null });
    return live.get(id);
  }
  rpc.on('disconnected', () => approvals.clear());
  rpc.on('event', message => {
    const p = message.params || {}, id = p.threadId || p.thread?.id;
    if (message.id !== undefined) {
      if (['item/commandExecution/requestApproval', 'item/fileChange/requestApproval', 'item/tool/requestUserInput'].includes(message.method)) {
        approvals.set(String(message.id), message);
      } else if (message.method === 'item/permissions/requestApproval') {
        rpc.write({ id: message.id, result: { permissions: {}, scope: 'turn' } });
      } else if (message.method === 'mcpServer/elicitation/request') {
        rpc.write({ id: message.id, result: { action: 'cancel', content: null } });
      } else rpc.write({ id: message.id, error: { code: -32601, message: 'Lanjutkan permintaan ini di desktop.' } });
      return;
    }
    if (message.method === 'serverRequest/resolved') approvals.delete(String(p.requestId));
    if (!id) return;
    if (message.method === 'turn/started') {
      cache(id).items.clear();
      cache(id).turn = p.turn;
    }
    if (message.method === 'turn/completed') {
      cache(id).turn = p.turn;
      for (const [key, request] of approvals) if (request.params.threadId === id) approvals.delete(key);
    }
    if (['item/started', 'item/completed'].includes(message.method)) cache(id).items.set(p.item.id, p.item);
    if (message.method === 'item/agentMessage/delta') {
      const item = cache(id).items.get(p.itemId) || { id: p.itemId, type: 'agentMessage', text: '' };
      item.text += p.delta;
      cache(id).items.set(p.itemId, item);
    }
  });
  async function scopedDirectory(directory) {
    let resolved;
    try { resolved = await realpath(directory); } catch { fail(400, 'Folder tidak ditemukan.'); }
    if (!inside(root, resolved)) fail(403, 'Folder berada di luar workspace yang diizinkan.');
    return resolved;
  }
  async function read(id) {
    const { thread } = await rpc.call('thread/read', { threadId: id });
    await scopedDirectory(thread.cwd);
    return thread;
  }
  async function resume(id) {
    if (!loaded.has(id)) {
      await rpc.call('thread/resume', { threadId: id, excludeTurns: true, ...(shared ? {} : policy) });
      loaded.add(id);
    }
  }
  function summary(thread) {
    return { id: thread.id, name: thread.name || thread.preview || 'Chat baru', cwd: thread.cwd,
      updatedAt: thread.updatedAt, status: thread.status, forkOnSend: !shared && !state.owned.includes(thread.id) };
  }
  function message(item) {
    if (item.type === 'userMessage') return { id: item.id, role: 'user', text: item.content.map(c => c.text || '').join('\n') };
    if (item.type === 'agentMessage' || item.type === 'plan') return { id: item.id, role: 'assistant', text: item.text || '' };
    if (item.type === 'commandExecution') return { id: item.id, role: 'tool', text: '$ ' + item.command + '\n' + (item.aggregatedOutput || '').slice(-4000) };
    if (item.type === 'fileChange') return { id: item.id, role: 'tool', text: 'Perubahan file: ' + item.changes.map(c => c.path).join(', ') };
    return null;
  }
  async function route(request, url) {
    const method = request.method;
    if (url.pathname === '/api/status' && method === 'GET') {
      const directories = await readdir(root, { withFileTypes: true });
      return { connected: rpc.ready, mode: shared ? 'shared' : 'standalone', root,
        workspaces: directories.filter(d => d.isDirectory() && !d.name.startsWith('.')).map(d => ({ name: d.name, path: path.join(root, d.name) })) };
    }
    if (!rpc.ready) fail(503, 'Codex tidak terhubung. Mulai ulang bridge.');
    if (url.pathname === '/api/threads' && method === 'GET') {
      const result = await rpc.call('thread/list', { limit: 50, sortKey: 'updated_at', sourceKinds: ['cli', 'vscode', 'appServer'], cursor: url.searchParams.get('cursor') });
      const threads = [];
      for (const thread of result.data) {
        try { await scopedDirectory(thread.cwd); threads.push(summary(thread)); } catch { /* Out of scope or removed workspace. */ }
      }
      return { threads, nextCursor: result.nextCursor };
    }
    if (url.pathname === '/api/threads' && method === 'POST') {
      const data = await body(request), cwd = await scopedDirectory(text(data.cwd, 4096));
      const name = data.name === undefined || data.name === '' ? null : text(data.name, 120);
      const { thread } = await rpc.call('thread/start', { cwd, ...policy });
      state.owned.push(thread.id);
      loaded.add(thread.id);
      await saveState(state);
      if (name) await rpc.call('thread/name/set', { threadId: thread.id, name });
      return { thread: summary({ ...thread, name: name || 'Chat baru' }) };
    }
    const match = /^\/api\/threads\/([a-zA-Z0-9_-]{1,100})(?:\/(messages|interrupt|approvals))?$/.exec(url.pathname);
    if (!match) fail(404, 'Endpoint tidak ditemukan.');
    const [, originalId, action] = match;
    const thread = await read(originalId);
    if (!action && method === 'GET') {
      // Subscribe only to threads owned by this bridge, or to the same shared process.
      if (shared || state.owned.includes(originalId)) await resume(originalId);
      const history = await rpc.call('thread/turns/list', { threadId: originalId, limit: 25, sortDirection: 'desc', itemsView: 'full', cursor: url.searchParams.get('cursor') });
      const items = new Map();
      for (const turn of [...history.data].reverse()) for (const item of turn.items || []) items.set(item.id, item);
      if (!url.searchParams.has('cursor')) for (const [id, item] of live.get(originalId)?.items || []) items.set(id, item);
      const turn = live.get(originalId)?.turn || history.data[0];
      return { thread: summary(thread), messages: [...items.values()].map(message).filter(Boolean), nextCursor: history.nextCursor,
        activeTurnId: turn?.status === 'inProgress' ? turn.id : null,
        error: turn?.error?.message || null,
        approvals: [...approvals.values()].filter(a => a.params.threadId === originalId).map(a => ({ ...a,
          preview: live.get(originalId)?.items.get(a.params.itemId) || null })) };
    }
    if (action === 'messages' && method === 'POST') {
      const data = await body(request), input = text(data.text), clientId = text(data.clientMessageId, 100);
      const key = `${originalId}:${clientId}`;
      if (sent.has(key)) {
        const previous = sent.get(key);
        if (previous.uncertain) fail(409, 'Pengiriman sebelumnya belum terkonfirmasi. Periksa riwayat sebelum membuat pesan baru.');
        return previous;
      }
      if (locks.has(originalId)) fail(409, 'Pesan sebelumnya masih dikirim.');
      locks.add(originalId);
      try {
        let id = originalId;
        if (!shared && !state.owned.includes(id)) {
          id = state.forks[id];
          if (!id) {
            const fork = await rpc.call('thread/fork', { threadId: originalId, excludeTurns: true, ...policy });
            id = fork.thread.id;
            state.owned.push(id);
            state.forks[originalId] = id;
            loaded.add(id);
            await saveState(state);
            await rpc.call('thread/name/set', { threadId: id, name: 'HP · ' + (thread.name || thread.preview || 'Lanjutan chat').slice(0, 90) });
          }
        }
        await resume(id);
        const current = await read(id);
        if (current.status?.type === 'active') fail(409, 'Codex masih bekerja. Tunggu selesai atau tekan Hentikan.');
        // Never retry automatically: turn/start may have succeeded even after a network timeout.
        let turn;
        // Retain an uncertain result so retrying after an RPC timeout cannot execute twice.
        sent.set(key, { uncertain: true });
        ({ turn } = await rpc.call('turn/start', { threadId: id, clientUserMessageId: clientId, input: [{ type: 'text', text: input, text_elements: [] }] }));
        const result = { threadId: id, turnId: turn.id, forked: id !== originalId };
        sent.set(key, result);
        if (sent.size > 1000) sent.delete(sent.keys().next().value);
        return result;
      } finally { locks.delete(originalId); }
    }
    if (action === 'interrupt' && method === 'POST') {
      if (!shared && !state.owned.includes(originalId)) fail(403, 'Chat ini dikontrol oleh desktop.');
      const data = await body(request);
      return rpc.call('turn/interrupt', { threadId: originalId, turnId: text(data.turnId, 100) });
    }
    if (action === 'approvals' && method === 'POST') {
      const data = await body(request), pending = approvals.get(String(data.id));
      if (!pending || pending.params.threadId !== originalId) fail(404, 'Permintaan sudah selesai.');
      let result;
      if (pending.method === 'item/tool/requestUserInput') {
        if (!data.answers || typeof data.answers !== 'object') fail(400, 'Jawaban diperlukan.');
        const answers = {};
        for (const q of pending.params.questions) {
          const answer = data.answers[q.id];
          if (typeof answer !== 'string' || answer.length > 8000) fail(400, 'Jawaban tidak valid.');
          answers[q.id] = { answers: [answer] };
        }
        result = { answers };
      } else {
        if (!['accept', 'decline', 'cancel'].includes(data.decision)) fail(400, 'Keputusan tidak valid.');
        result = { decision: data.decision };
      }
      rpc.write({ id: pending.id, result });
      approvals.delete(String(data.id));
      return { ok: true };
    }
    fail(405, 'Metode tidak diizinkan.');
  }
  const server = createServer(async (request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Connection', 'close');
    try {
      // No browser clients: blocks cross-origin requests, including localhost CSRF.
      if (request.headers.origin) fail(403, 'Gunakan aplikasi Android.');
      if (!authorized(request.headers.authorization, token)) fail(401, 'Token salah. Periksa pengaturan koneksi.');
      const result = await route(request, new URL(request.url, 'http://localhost'));
      response.writeHead(200);
      response.end(JSON.stringify(result));
    } catch (error) {
      response.writeHead(error.status || 502);
      response.end(JSON.stringify({ error: error.message }));
    }
  });
  server.requestTimeout = 70000;
  server.headersTimeout = 10000;
  return server;
}
