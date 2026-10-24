-- Sliding-window rate limiter state (login, signup, recover, password change). Rows older than 60 minutes
-- are pruned opportunistically by the Worker.
CREATE TABLE rate_hits (
  k TEXT NOT NULL,
  ts INTEGER NOT NULL
);
CREATE INDEX rate_hits_k_ts ON rate_hits (k, ts);
CREATE INDEX rate_hits_ts ON rate_hits (ts);
