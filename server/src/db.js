// Thin database interface: { query(text, params) -> { rows }, close(), persistent }.
// Real mode uses pg; in-memory mode uses pg-mem (dev/test only, a devDependency).
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const MIGRATIONS = fileURLToPath(new URL('./migrations.sql', import.meta.url));

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
    close: () => pool.end(),
  };
}

// Idempotent (all statements use IF NOT EXISTS). Statements run one at a time.
export async function migrate(db) {
  const sql = await readFile(MIGRATIONS, 'utf8');
  for (const stmt of sql.split(';').map((s) => s.trim()).filter(Boolean)) {
    await db.query(stmt);
  }
}
