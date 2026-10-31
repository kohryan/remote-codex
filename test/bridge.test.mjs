import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, mkdir, symlink, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createBridge, authorized, inside } from '../server/bridge.mjs';

const token = 'test-token-0123456789-0123456789-0123456789';
class FakeCodex extends EventEmitter {
  ready = true;
  calls = [];
  replies = [];
  threads = new Map();
  history = new Map();
  write(message) { this.replies.push(message); }
  async call(method, params) {
    this.calls.push({ method, params });
    if (method === 'thread/read') return { thread: this.threads.get(params.threadId) };
    if (method === 'thread/list') return { data: [...this.threads.values()], nextCursor: null };
    if (method === 'thread/turns/list') return { data: this.history.get(params.threadId) || [], nextCursor: null };
    if (method === 'thread/resume') return { thread: this.threads.get(params.threadId) };
    if (method === 'thread/start' || method === 'thread/fork') {
      const id = method === 'thread/fork' ? 'forked' : 'new';
      const thread = { id, cwd: params.cwd || this.threads.get(params.threadId).cwd, name: id, status: { type: 'idle' } };
      this.threads.set(id, thread);
      return { thread };
    }
    if (method === 'turn/start') {
      if (this.failSend) throw new Error('Timeout');
      return { turn: { id: 'turn-1', status: 'inProgress' } };
    }
    return {};
  }
}
async function fixture(t, shared = false) {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'remote-codex-')));
  await mkdir(path.join(root, 'repo'));
  const rpc = new FakeCodex(), state = { owned: ['owned'], forks: {} };
  for (const id of ['desktop', 'owned']) rpc.threads.set(id, { id, name: id, cwd: path.join(root, 'repo'), status: { type: 'idle' } });
  const server = createBridge({ rpc, token, root, shared, state });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(root, { recursive: true, force: true }); });
  async function request(route, data, headers = {}) {
    const response = await fetch(`http://127.0.0.1:${server.address().port}${route}`, {
      method: data === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${token}`, ...headers },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    });
    return { status: response.status, body: await response.json() };
  }
  return { root, rpc, state, request };
}
test('authentication is exact; workspace containment rejects sibling prefixes', () => {
  assert.equal(authorized(`Bearer ${token}`, token), true);
  for (const bad of [null, '', `Bearer ${token}x`, token]) assert.equal(authorized(bad, token), false);
  assert.equal(inside('/workspace', '/workspace/repo'), true);
  assert.equal(inside('/workspace', '/workspace-other'), false);
  assert.equal(inside('/workspace', '/workspace/../secret'), false);
});
test('token and Origin checks happen before contacting Codex', async t => {
  const { request, rpc } = await fixture(t);
  assert.equal((await request('/api/status', undefined, { Authorization: 'wrong' })).status, 401);
  assert.equal((await request('/api/status', undefined, { Origin: 'https://evil.example' })).status, 403);
  assert.equal(rpc.calls.length, 0);
});
test('creation rejects traversal and symlink escape; listing hides out-of-scope history', async t => {
  const { request, rpc, root } = await fixture(t);
  await symlink(tmpdir(), path.join(root, 'escape'));
  assert.equal((await request('/api/threads', { cwd: tmpdir() })).status, 403);
  assert.equal((await request('/api/threads', { cwd: path.join(root, 'escape') })).status, 403);
  rpc.threads.set('secret', { id: 'secret', cwd: tmpdir() });
  const list = await request('/api/threads');
  assert.deepEqual(list.body.threads.map(t => t.id), ['desktop', 'owned']);
  assert.equal((await request('/api/threads/secret')).status, 403);
  const created = await request('/api/threads', { cwd: path.join(root, 'repo'), name: 'New chat' });
  assert.equal(created.status, 200);
  const start = rpc.calls.find(c => c.method === 'thread/start');
  assert.equal(start.params.sandbox, 'workspace-write');
  assert.equal(start.params.approvalPolicy, 'on-request');
});
test('reading desktop chat does not resume it; sending forks once and deduplicates', async t => {
  const { request, rpc } = await fixture(t);
  assert.equal((await request('/api/threads/desktop')).status, 200);
  assert.equal(rpc.calls.some(c => c.method === 'thread/resume'), false);
  const input = { text: 'hello', clientMessageId: 'unique-message' };
  const sent = await request('/api/threads/desktop/messages', input);
  assert.equal(sent.body.threadId, 'forked');
  assert.equal(sent.body.forked, true);
  assert.deepEqual((await request('/api/threads/desktop/messages', input)).body, sent.body);
  await request('/api/threads/desktop/messages', { text: 'next', clientMessageId: 'another-message' });
  assert.equal(rpc.calls.filter(c => c.method === 'thread/fork').length, 1);
  assert.equal(rpc.calls.filter(c => c.method === 'turn/start').length, 2);
  assert.equal(rpc.calls.filter(c => c.method === 'turn/start').every(c => c.params.threadId === 'forked'), true);
  assert.equal((await request('/api/threads/desktop/interrupt', { turnId: 'turn' })).status, 403);
});
test('uncertain turn result cannot execute again with the same message id', async t => {
  const { request, rpc } = await fixture(t);
  rpc.failSend = true;
  const input = { text: 'run', clientMessageId: 'one' };
  assert.equal((await request('/api/threads/owned/messages', input)).status, 502);
  assert.equal((await request('/api/threads/owned/messages', input)).status, 409);
  assert.equal(rpc.calls.filter(c => c.method === 'turn/start').length, 1);
});
test('shared connection resumes original chat; active threads reject another turn', async t => {
  const { request, rpc } = await fixture(t, true);
  const sent = await request('/api/threads/desktop/messages', { text: 'hello', clientMessageId: 'id' });
  assert.equal(sent.body.threadId, 'desktop');
  assert.equal(rpc.calls.some(c => c.method === 'thread/fork'), false);
  rpc.threads.get('desktop').status = { type: 'active' };
  assert.equal((await request('/api/threads/desktop/messages', { text: 'again', clientMessageId: 'id2' })).status, 409);
});
test('streamed deltas merge once with persisted items; approvals are scoped and explicit', async t => {
  const { request, rpc } = await fixture(t);
  rpc.history.set('owned', [{ id: 'turn', status: 'inProgress', items: [{ id: 'agent', type: 'agentMessage', text: 'Hi' }] }]);
  rpc.emit('event', { method: 'turn/started', params: { threadId: 'owned', turn: { id: 'turn', status: 'inProgress' } } });
  rpc.emit('event', { method: 'item/started', params: { threadId: 'owned', item: { id: 'agent', type: 'agentMessage', text: '' } } });
  rpc.emit('event', { method: 'item/agentMessage/delta', params: { threadId: 'owned', itemId: 'agent', delta: 'Hi there' } });
  rpc.emit('event', { id: 90, method: 'item/commandExecution/requestApproval', params: { threadId: 'owned', turnId: 'turn', command: 'npm test' } });
  const snapshot = await request('/api/threads/owned');
  assert.deepEqual(snapshot.body.messages, [{ id: 'agent', role: 'assistant', text: 'Hi there' }]);
  assert.equal(snapshot.body.approvals.length, 1);
  assert.equal(rpc.replies.length, 0);
  assert.equal((await request('/api/threads/desktop/approvals', { id: 90, decision: 'accept' })).status, 404);
  assert.equal((await request('/api/threads/owned/approvals', { id: 90, decision: 'acceptForSession' })).status, 400);
  assert.equal((await request('/api/threads/owned/approvals', { id: 90, decision: 'accept' })).status, 200);
  assert.deepEqual(rpc.replies[0], { id: 90, result: { decision: 'accept' } });
  assert.equal((await request('/api/threads/owned')).body.approvals.length, 0);
});
test('user input answers use protocol shape; unsupported grants fail closed', async t => {
  const { request, rpc } = await fixture(t);
  rpc.emit('event', { id: 8, method: 'item/tool/requestUserInput', params: { threadId: 'owned', questions: [{ id: 'choice', question: 'Choose' }] } });
  assert.equal((await request('/api/threads/owned/approvals', { id: 8, answers: { choice: 'Yes' } })).status, 200);
  assert.deepEqual(rpc.replies[0], { id: 8, result: { answers: { choice: { answers: ['Yes'] } } } });
  rpc.emit('event', { id: 9, method: 'item/permissions/requestApproval', params: { threadId: 'owned', permissions: {} } });
  assert.deepEqual(rpc.replies[1].result.permissions, {});
});
