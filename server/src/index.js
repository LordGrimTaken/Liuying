import { createApp } from './app.js';
import { createMemoryDb, createPgDb, migrate } from './db.js';

const production = process.env.NODE_ENV === 'production';
const port = Number(process.env.PORT) || 3000;

let db;
if (process.env.DATABASE_URL) {
  db = createPgDb(process.env.DATABASE_URL, { ssl: /^(1|true)$/i.test(process.env.DATABASE_SSL || '') });
} else {
  if (production) {
    console.error('DATABASE_URL is required in production');
    process.exit(1);
  }
  console.warn('DEV in-memory database, accounts will not persist');
  db = await createMemoryDb();
}

await migrate(db);
const app = createApp({ db, production });
const server = app.listen(port, () => {
  console.log(JSON.stringify({ level: 'info', msg: 'listening', port, production }));
});

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => server.close(() => db.close().finally(() => process.exit(0))));
}
