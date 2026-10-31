import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { EventEmitter } from 'node:events';

export class CodexRpc extends EventEmitter {
  pending = new Map();
  counter = 0;
  ready = false;
  constructor(binary, args) {
    super();
    this.process = spawn(binary, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    this.process.stderr.on('data', () => {}); // Codex stderr can contain workspace context.
    createInterface({ input: this.process.stdout }).on('line', line => {
      let message;
      try { message = JSON.parse(line); } catch { return; }
      if (message.method) this.emit('event', message);
      else if (this.pending.has(message.id)) {
        const { resolve, reject, timeout } = this.pending.get(message.id);
        clearTimeout(timeout);
        this.pending.delete(message.id);
        message.error ? reject(new Error(message.error.message)) : resolve(message.result);
      }
    });
    const disconnected = () => {
      this.ready = false;
      for (const request of this.pending.values()) {
        clearTimeout(request.timeout);
        request.reject(new Error('Koneksi Codex terputus. Mulai ulang bridge.'));
      }
      this.pending.clear();
      this.emit('disconnected');
    };
    this.process.on('error', disconnected);
    this.process.on('exit', disconnected);
    this.process.stdin.on('error', () => {});
  }
  write(message) {
    if (this.process.exitCode !== null || this.process.stdin.destroyed) throw new Error('Codex tidak terhubung.');
    this.process.stdin.write(JSON.stringify(message) + '\n');
  }
  call(method, params = {}) {
    const id = ++this.counter;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('Codex belum menjawab. Periksa status sebelum mencoba lagi.'));
      }, 60000);
      this.pending.set(id, { resolve, reject, timeout });
      try { this.write({ id, method, params }); }
      catch (error) { clearTimeout(timeout); this.pending.delete(id); reject(error); }
    });
  }
  async initialize() {
    await this.call('initialize', {
      clientInfo: { name: 'remote_codex', title: 'Remote Codex', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    });
    this.write({ method: 'initialized' });
    this.ready = true;
  }
  close() { this.process.kill('SIGTERM'); }
}
