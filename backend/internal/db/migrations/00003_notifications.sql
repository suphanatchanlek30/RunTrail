-- +goose Up
-- In-app notifications created from real events (run verified, new PR, weekly goal, passed in ranking, share viewed).
CREATE TABLE notifications (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type       TEXT NOT NULL,
  title      TEXT NOT NULL,
  body       TEXT NOT NULL,
  run_id     UUID,                 -- the run to open when tapped (may be deleted later)
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  read_at    TIMESTAMPTZ
);
CREATE INDEX notifications_user ON notifications (user_id, created_at DESC, id DESC);

ALTER TABLE runs ADD COLUMN share_views INTEGER NOT NULL DEFAULT 0;

-- +goose Down
ALTER TABLE runs DROP COLUMN share_views;
DROP TABLE notifications;
