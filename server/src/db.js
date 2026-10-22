// Thin database interface: { query(text, params) -> { rows }, close(), persistent }.
// Real mode uses pg; in-memory mode uses pg-mem (dev/test only, a devDependency).
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import pg from 'pg';
import { usernameKey } from './auth.js';

const MIGRATIONS = fileURLToPath(new URL('./migrations.sql', import.meta.url));


function withTransaction(pool) {
  return async (fn) => {
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const out = await fn({ query: (text, params) => client.query(text, params) });
      await client.query('COMMIT');
      return out;
    } catch (err) {
      try { await client.query('ROLLBACK'); } catch { /* ignore */ }
      throw err;
    } finally {
      client.release();
    }
  };
}

export function createPgDb(connectionString, { ssl = false } = {}) {
  const pool = new pg.Pool({
    connectionString,
    max: 10,
    ssl: ssl ? { rejectUnauthorized: false } : undefined,
  });
  pool.on('error', (err) => {
    console.error(JSON.stringify({ level: 'error', msg: 'pg pool error', error: err.message }));
  });
  return {
    persistent: true,
    query: (text, params) => pool.query(text, params),
    transaction: withTransaction(pool),
    close: () => pool.end(),
  };
}

export async function createMemoryDb() {
  const { newDb } = await import('pg-mem'); // dynamic: not installed with --omit=dev
  const mem = newDb();
  const { Pool } = mem.adapters.createPg();
  const pool = new Pool();
  return {
    persistent: false,
    query: (text, params) => pool.query(text, params),
    transaction: withTransaction(pool),
    close: () => pool.end(),
  };
}

// Idempotent (all statements use IF NOT EXISTS). Statements run one at a time.
export async function migrate(db) {
  const sql = await readFile(MIGRATIONS, 'utf8');
  for (const stmt of sql.split(';').map((s) => s.trim()).filter(Boolean)) {
    await db.query(stmt);
  }
  await backfillUsernameKeys(db);
}

// Unicode usernames: make username_lower the NFKC + case-folded key. Idempotent; ASCII rows
// already match so nothing changes for existing accounts. A key collision is left untouched.
export async function backfillUsernameKeys(db) {
  const r = await db.query('SELECT id, username, username_lower FROM users');
  for (const u of r.rows) {
    const key = usernameKey(u.username);
    if (key === u.username_lower) continue;
    try { await db.query('UPDATE users SET username_lower = $1 WHERE id = $2', [key, u.id]); } catch { /* collision: keep old key */ }
  }
}
