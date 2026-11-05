-- Watch party rooms: one row per room, one row per participant.
-- Only the host writes playback state; everyone else polls. Rooms expire 12 hours after the last update
-- and are pruned opportunistically when a new room is created.
CREATE TABLE party_rooms (
  code TEXT PRIMARY KEY,
  host_user INTEGER NOT NULL,
  media_id TEXT NOT NULL,
  episode_id TEXT,
  position_ms INTEGER NOT NULL,
  playing INTEGER NOT NULL,
  rev INTEGER NOT NULL DEFAULT 1,
  host_name TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX party_rooms_updated ON party_rooms (updated_at);

CREATE TABLE party_members (
  code TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  joined_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (code, user_id)
);
CREATE INDEX party_members_code ON party_members (code);
