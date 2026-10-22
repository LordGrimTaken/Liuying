CREATE TABLE IF NOT EXISTS users (
  id uuid PRIMARY KEY,
  username text NOT NULL,
  username_lower text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  created_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS sessions_expires_at_idx ON sessions (expires_at);

CREATE TABLE IF NOT EXISTS user_sources (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  url text NOT NULL,
  position integer NOT NULL,
  enabled boolean NOT NULL,
  PRIMARY KEY (user_id, url)
);

CREATE TABLE IF NOT EXISTS user_library (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  id text NOT NULL,
  type text NOT NULL,
  name text NOT NULL,
  poster text,
  added_at timestamptz NOT NULL,
  PRIMARY KEY (user_id, id)
);

CREATE TABLE IF NOT EXISTS user_progress (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  media_id text NOT NULL,
  episode_id text NOT NULL,
  position_ms bigint NOT NULL,
  duration_ms bigint NOT NULL,
  watched boolean NOT NULL,
  dismissed boolean NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, media_id, episode_id)
);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  data text NOT NULL,
  updated_at timestamptz NOT NULL
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS recovery_hash text;

ALTER TABLE users ADD COLUMN IF NOT EXISTS recovery_created_at timestamptz
