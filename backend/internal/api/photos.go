package api

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/suphanatchanlek30/runtrail/backend/internal/auth"
	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
	"github.com/suphanatchanlek30/runtrail/backend/internal/storage"
)

const (
	maxPhotoBytes    = 5 << 20
	uploadURLTTL     = 10 * time.Minute
	pendingUploadTTL = 24 * time.Hour
)

var photoExt = map[string]string{"image/jpeg": "jpg", "image/webp": "webp"}

type uploadURLReq struct {
	ContentType string `json:"content_type" validate:"required"`
	Size        int64  `json:"size" validate:"required,gt=0"`
}

type uploadURLResp struct {
	UploadURL string            `json:"upload_url"`
	Method    string            `json:"method"`
	Headers   map[string]string `json:"headers"`
	Key       string            `json:"key"`
	ExpiresIn int               `json:"expires_in"`
}

func (r uploadURLReq) check() (string, error) {
	ext, ok := photoExt[strings.ToLower(r.ContentType)]
	if !ok {
		return "", httpx.Field("content_type", "oneof", "รองรับเฉพาะ image/jpeg หรือ image/webp")
	}
	if r.Size > maxPhotoBytes {
		return "", httpx.Field("size", "max", "รูปต้องไม่เกิน 5 MB")
	}
	return ext, nil
}

// newUpload reserves a random key and returns a presigned PUT the phone uploads to directly.
func (s *Server) newUpload(ctx context.Context, uid uuid.UUID, runID *uuid.UUID, prefix string, r uploadURLReq) (uploadURLResp, error) {
	ext, err := r.check()
	if err != nil {
		return uploadURLResp{}, err
	}
	rnd, err := auth.ShareCode()
	if err != nil {
		return uploadURLResp{}, err
	}
	key := fmt.Sprintf("%s/%s.%s", prefix, strings.ToLower(rnd)+uuid.NewString()[:8], ext)
	ct := strings.ToLower(r.ContentType)
	url, err := s.store.PresignPut(ctx, key, ct, uploadURLTTL)
	if err != nil {
		return uploadURLResp{}, err
	}
	now := s.now()
	if _, err := s.pool.Exec(ctx, `INSERT INTO photo_uploads (key, user_id, run_id, expires_at, created_at) VALUES ($1,$2,$3,$4,$5)`,
		key, uid, runID, now.Add(pendingUploadTTL), now); err != nil {
		return uploadURLResp{}, err
	}
	return uploadURLResp{UploadURL: url, Method: "PUT", Headers: map[string]string{"Content-Type": ct},
		Key: key, ExpiresIn: int(uploadURLTTL / time.Second)}, nil
}

type confirmReq struct {
	Key string `json:"key" validate:"required,max=300"`
}

// confirmUpload checks the object really exists in S3 and is within limits.
func (s *Server) confirmUpload(ctx context.Context, uid uuid.UUID, runID *uuid.UUID, key string) error {
	var ok bool
	err := s.pool.QueryRow(ctx, `SELECT true FROM photo_uploads WHERE key = $1 AND user_id = $2
		AND run_id IS NOT DISTINCT FROM $3`, key, uid, runID).Scan(&ok)
	if noRows(err) {
		return httpx.Field("key", "unknown", "ไม่พบคำขออัปโหลดนี้")
	}
	if err != nil {
		return err
	}
	obj, err := s.store.Head(ctx, key)
	if errors.Is(err, storage.ErrNotFound) {
		return httpx.BadRequest("upload_missing", "ยังไม่พบไฟล์ที่อัปโหลด")
	}
	if err != nil {
		return err
	}
	if obj.Size > maxPhotoBytes || obj.Size == 0 {
		_ = s.store.Delete(ctx, key)
		_, _ = s.pool.Exec(ctx, `DELETE FROM photo_uploads WHERE key = $1`, key)
		return httpx.BadRequest("upload_invalid", "ไฟล์ว่างหรือใหญ่เกิน 5 MB")
	}
	return nil
}

func (s *Server) photoUploadURL(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	var r uploadURLReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	if _, err := s.ownRun(ctx, s.pool, uid, id, false); err != nil {
		return err
	}
	out, err := s.newUpload(ctx, uid, &id, fmt.Sprintf("runs/%s/%s", uid, id), r)
	if err != nil {
		return err
	}
	return c.JSON(out)
}

func (s *Server) photoConfirm(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	var r confirmReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	if _, err := s.ownRun(ctx, s.pool, uid, id, false); err != nil {
		return err
	}
	if err := s.confirmUpload(ctx, uid, &id, r.Key); err != nil {
		return err
	}
	var old *string
	var out *runJSON
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT photo_key FROM runs WHERE id = $1 FOR UPDATE`, id).Scan(&old); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE runs SET photo_key = $2, updated_at = $3 WHERE id = $1`, id, r.Key, s.now()); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM photo_uploads WHERE key = $1`, r.Key); err != nil {
			return err
		}
		out, err = s.ownRun(ctx, tx, uid, id, true)
		return err
	})
	if err != nil {
		return err
	}
	if old != nil && *old != r.Key {
		s.deleteObjects([]string{*old})
	}
	return c.JSON(out)
}

func (s *Server) photoDelete(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	cur, err := s.ownRun(ctx, s.pool, uid, id, false)
	if err != nil {
		return err
	}
	if cur.photoKey != nil {
		if _, err := s.pool.Exec(ctx, `UPDATE runs SET photo_key = NULL, updated_at = $2 WHERE id = $1`, id, s.now()); err != nil {
			return err
		}
		s.deleteObjects([]string{*cur.photoKey})
	}
	return c.SendStatus(fiber.StatusNoContent)
}

func (s *Server) avatarUploadURL(c fiber.Ctx) error {
	var r uploadURLReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	uid := userID(c)
	out, err := s.newUpload(c.Context(), uid, nil, "avatars/"+uid.String(), r)
	if err != nil {
		return err
	}
	return c.JSON(out)
}

func (s *Server) avatarConfirm(c fiber.Ctx) error {
	var r confirmReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	if err := s.confirmUpload(ctx, uid, nil, r.Key); err != nil {
		return err
	}
	var old *string
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT avatar_key FROM users WHERE id = $1 FOR UPDATE`, uid).Scan(&old); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE users SET avatar_key = $2, updated_at = $3 WHERE id = $1`, uid, r.Key, s.now()); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `DELETE FROM photo_uploads WHERE key = $1`, r.Key)
		return err
	})
	if err != nil {
		return err
	}
	if old != nil && *old != r.Key {
		s.deleteObjects([]string{*old})
	}
	s.lb.clear()
	u, err := s.loadUser(ctx, s.pool, uid)
	if err != nil {
		return err
	}
	return c.JSON(u)
}

func (s *Server) avatarDelete(c fiber.Ctx) error {
	ctx, uid := c.Context(), userID(c)
	var old *string
	err := s.pool.QueryRow(ctx, `UPDATE users u SET avatar_key = NULL, updated_at = $2
		FROM (SELECT avatar_key FROM users WHERE id = $1 FOR UPDATE) prev
		WHERE u.id = $1 RETURNING prev.avatar_key`, uid, s.now()).Scan(&old)
	if err != nil {
		return err
	}
	if old != nil {
		s.deleteObjects([]string{*old})
	}
	s.lb.clear()
	return c.SendStatus(fiber.StatusNoContent)
}

// CleanupUploads deletes files that were never confirmed (the phone gave up or crashed).
func (s *Server) CleanupUploads(ctx context.Context) (int, error) {
	rows, err := s.pool.Query(ctx, `DELETE FROM photo_uploads WHERE expires_at < $1 RETURNING key`, s.now())
	if err != nil {
		return 0, err
	}
	keys, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return 0, err
	}
	for _, k := range keys {
		if err := s.store.Delete(ctx, k); err != nil {
			slog.Warn("cleanup delete failed", "key", k, "err", err)
		}
	}
	return len(keys), nil
}

// RunJobs runs background maintenance until ctx is cancelled.
func (s *Server) RunJobs(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		if n, err := s.CleanupUploads(ctx); err != nil {
			slog.Warn("cleanup uploads", "err", err)
		} else if n > 0 {
			slog.Info("cleaned abandoned uploads", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
