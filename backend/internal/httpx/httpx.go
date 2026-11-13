// Package httpx holds the shared error format, JSON decoding and validation used by every handler.
package httpx

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"reflect"
	"strings"

	"github.com/go-playground/validator/v10"
	"github.com/gofiber/fiber/v3"
)

// Error is the single error shape returned by the API:
// {"error":{"code":"...","message":"...","fields":{...}}}
type Error struct {
	Status  int               `json:"-"`
	Code    string            `json:"code"`
	Message string            `json:"message"`
	Fields  map[string]string `json:"fields,omitempty"`
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

func E(status int, code, msg string) *Error { return &Error{Status: status, Code: code, Message: msg} }

func BadRequest(code, msg string) *Error { return E(fiber.StatusBadRequest, code, msg) }
func NotFound(msg string) *Error         { return E(fiber.StatusNotFound, "not_found", msg) }
func Unauthorized(code, msg string) *Error {
	return E(fiber.StatusUnauthorized, code, msg)
}

// ErrorHandler turns any returned error into the JSON error shape.
func ErrorHandler(c fiber.Ctx, err error) error {
	var he *Error
	var fe *fiber.Error
	switch {
	case errors.As(err, &he):
	case errors.As(err, &fe):
		he = &Error{Status: fe.Code, Code: fiberCode(fe.Code), Message: fe.Message}
		if fe.Code == fiber.StatusNotFound {
			he.Message = "ไม่พบ endpoint นี้"
		}
	default:
		slog.Error("unhandled error", "err", err, "path", c.Path(), "method", c.Method())
		he = E(fiber.StatusInternalServerError, "internal", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์")
	}
	return c.Status(he.Status).JSON(fiber.Map{"error": he})
}

func fiberCode(status int) string {
	switch status {
	case fiber.StatusNotFound:
		return "not_found"
	case fiber.StatusMethodNotAllowed:
		return "method_not_allowed"
	case fiber.StatusRequestEntityTooLarge:
		return "body_too_large"
	case fiber.StatusTooManyRequests:
		return "rate_limited"
	case fiber.StatusUnsupportedMediaType:
		return "unsupported_media_type"
	default:
		if status >= 500 {
			return "internal"
		}
		return "bad_request"
	}
}

// MaxJSONBody is the largest JSON body accepted after gzip is undone.
const MaxJSONBody = 5 << 20

// Decode reads the request body (gzip allowed) into v and validates it.
// We undo gzip ourselves so the size cap applies to the decompressed bytes (gzip bomb guard).
func Decode(c fiber.Ctx, v any) error {
	raw := c.BodyRaw()
	var r io.Reader = bytes.NewReader(raw)
	switch enc := strings.ToLower(strings.TrimSpace(c.Get(fiber.HeaderContentEncoding))); enc {
	case "", "identity":
	case "gzip":
		gz, err := gzip.NewReader(r)
		if err != nil {
			return BadRequest("bad_gzip", "ข้อมูล gzip ไม่ถูกต้อง")
		}
		defer gz.Close()
		r = gz
	default:
		return E(fiber.StatusUnsupportedMediaType, "unsupported_encoding", "รองรับเฉพาะ gzip")
	}
	body, err := io.ReadAll(io.LimitReader(r, MaxJSONBody+1))
	if err != nil {
		return BadRequest("bad_gzip", "ข้อมูล gzip ไม่ถูกต้อง")
	}
	if len(body) > MaxJSONBody {
		return E(fiber.StatusRequestEntityTooLarge, "body_too_large", "ข้อมูลใหญ่เกิน 5 MB")
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return BadRequest("empty_body", "ไม่มีข้อมูลที่ส่งมา")
	}
	if err := json.Unmarshal(body, v); err != nil {
		var ute *json.UnmarshalTypeError
		if errors.As(err, &ute) && ute.Field != "" {
			return &Error{Status: 400, Code: "validation_failed", Message: "ข้อมูลไม่ถูกต้อง",
				Fields: map[string]string{ute.Field: "invalid_type"}}
		}
		return BadRequest("bad_json", "รูปแบบ JSON ไม่ถูกต้อง")
	}
	return Validate(v)
}

var validate = func() *validator.Validate {
	v := validator.New(validator.WithRequiredStructEnabled())
	v.RegisterTagNameFunc(func(f reflect.StructField) string {
		name := strings.SplitN(f.Tag.Get("json"), ",", 2)[0]
		if name == "-" {
			return ""
		}
		return name
	})
	return v
}()

// Validate runs struct tags and maps failures to {"field":"rule"}.
func Validate(v any) error {
	err := validate.Struct(v)
	if err == nil {
		return nil
	}
	var ve validator.ValidationErrors
	if !errors.As(err, &ve) {
		return BadRequest("validation_failed", err.Error())
	}
	fields := map[string]string{}
	for _, fe := range ve {
		// Namespace drops the top-level struct name, keeps nested paths like points[3].
		ns := fe.Namespace()
		if i := strings.Index(ns, "."); i >= 0 {
			ns = ns[i+1:]
		}
		fields[ns] = fe.Tag()
	}
	return &Error{Status: fiber.StatusBadRequest, Code: "validation_failed", Message: "ข้อมูลไม่ถูกต้อง", Fields: fields}
}

// Field builds a single-field validation error.
func Field(field, rule, msg string) *Error {
	return &Error{Status: fiber.StatusBadRequest, Code: "validation_failed", Message: msg,
		Fields: map[string]string{field: rule}}
}

// Optional distinguishes "field missing" from "field set to null" in PATCH bodies.
type Optional[T any] struct {
	Set   bool
	Null  bool
	Value T
}

func (o *Optional[T]) UnmarshalJSON(b []byte) error {
	o.Set = true
	if string(b) == "null" {
		o.Null = true
		return nil
	}
	if err := json.Unmarshal(b, &o.Value); err != nil {
		return fmt.Errorf("optional: %w", err)
	}
	return nil
}
