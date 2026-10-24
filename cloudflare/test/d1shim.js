// Minimal D1Database lookalike over node:sqlite, so the real Worker code (src/api.js, src/sync.js) runs under node --test.
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

const MIGRATIONS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../migrations');

export function createD1() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec('PRAGMA foreign_keys = ON');
  for (const f of fs.readdirSync(MIGRATIONS).sort()) sqlite.exec(fs.readFileSync(path.join(MIGRATIONS, f), 'utf8'));
  const exec = (sql, params) => {
    const st = sqlite.prepare(sql);
    const rows = st.all(...params);
    return { results: rows, success: true, meta: {} };
  };
  const stmt = (sql, params = []) => ({
    sql, params,
    bind: (...p) => stmt(sql, p),
    async all() { return exec(sql, params); },
    async run() { return exec(sql, params); },
    async first() { return exec(sql, params).results[0] ?? null; },
  });
  return {
    sqlite,
    prepare: (sql) => stmt(sql),
    async batch(list) {
      sqlite.exec('BEGIN');
      try {
        const out = list.map((s) => exec(s.sql, s.params));
        sqlite.exec('COMMIT');
        return out;
      } catch (e) { sqlite.exec('ROLLBACK'); throw e; }
    },
  };
}
