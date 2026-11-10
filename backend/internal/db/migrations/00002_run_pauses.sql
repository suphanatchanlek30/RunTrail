-- +goose Up
-- Pause windows [[start_ms, end_ms], ...]; distance inside them is not counted (same as the app).
ALTER TABLE run_tracks ADD COLUMN pauses JSONB NOT NULL DEFAULT '[]';

-- +goose Down
ALTER TABLE run_tracks DROP COLUMN pauses;
