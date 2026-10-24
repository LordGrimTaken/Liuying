-- Liuying on D1 (SQLite). Translation of server/src/migrations.sql:
--   uuid -> TEXT, timestamptz -> INTEGER (epoch ms), boolean -> INTEGER 0/1, bigint -> INTEGER.

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  username_lower TEXT NOT NULL UNIQUE,
  -- NULL for accounts imported from the old Render/scrypt database: they cannot log in with a password
  -- until the owner sets a new one (recovery code or re-parenting, see docs/cloudflare.md).
  password_hash TEXT,
  created_at INTEGER NOT NULL,
  recovery_hash TEXT,
  recovery_created_at INTEGER,
  needs_new_password INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);
CREATE INDEX sessions_user_idx ON sessions (user_id, created_at);

CREATE TABLE user_sources (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  url TEXT NOT NULL,
  position INTEGER NOT NULL,
  enabled INTEGER NOT NULL,
  PRIMARY KEY (user_id, url)
) WITHOUT ROWID;

CREATE TABLE user_library (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  id TEXT NOT NULL,
  type TEXT NOT NULL,
  name TEXT NOT NULL,
  poster TEXT,
  added_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, id)
) WITHOUT ROWID;

CREATE TABLE user_progress (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  media_id TEXT NOT NULL,
  episode_id TEXT NOT NULL,
  position_ms INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  watched INTEGER NOT NULL,
  dismissed INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, media_id, episode_id)
) WITHOUT ROWID;

CREATE TABLE user_settings (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  data TEXT NOT NULL,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;
