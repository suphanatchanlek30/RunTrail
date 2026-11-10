// Package auth holds password hashing, JWT access tokens and opaque refresh tokens.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/argon2"
)

// argon2id parameters (OWASP minimum: 19 MiB, 2 passes, 1 lane).
const (
	argonMemory  = 19 * 1024
	argonTime    = 2
	argonThreads = 1
	argonKeyLen  = 32
	saltLen      = 16
)

// HashPassword returns a PHC string: $argon2id$v=19$m=..,t=..,p=..$salt$hash
func HashPassword(pw string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := argon2.IDKey([]byte(pw), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, argonMemory, argonTime, argonThreads, b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

var ErrBadHash = errors.New("malformed password hash")

func VerifyPassword(pw, encoded string) (bool, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, ErrBadHash
	}
	var m, t uint32
	var p uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil {
		return false, ErrBadHash
	}
	b64 := base64.RawStdEncoding
	salt, err1 := b64.DecodeString(parts[4])
	want, err2 := b64.DecodeString(parts[5])
	if err1 != nil || err2 != nil {
		return false, ErrBadHash
	}
	got := argon2.IDKey([]byte(pw), salt, t, m, p, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

// DummyHash lets login spend the same time when the email does not exist (no user enumeration).
var DummyHash, _ = HashPassword("runtrail-dummy-password")

// NewRefreshToken returns the token for the client and the sha256 we store.
func NewRefreshToken() (string, []byte, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", nil, err
	}
	plain := base64.RawURLEncoding.EncodeToString(b)
	return plain, HashToken(plain), nil
}

func HashToken(plain string) []byte {
	h := sha256.Sum256([]byte(plain))
	return h[:]
}

// JWT signs short-lived access tokens.
type JWT struct {
	Secret []byte
	TTL    time.Duration
}

const issuer = "runtrail"

func (j JWT) Sign(userID uuid.UUID, now time.Time) (string, time.Time, error) {
	exp := now.Add(j.TTL)
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.RegisteredClaims{
		Subject:   userID.String(),
		Issuer:    issuer,
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(exp),
	})
	s, err := tok.SignedString(j.Secret)
	return s, exp, err
}

var ErrInvalidToken = errors.New("invalid token")

func (j JWT) Parse(token string, now time.Time) (uuid.UUID, error) {
	claims := &jwt.RegisteredClaims{}
	_, err := jwt.ParseWithClaims(token, claims, func(*jwt.Token) (any, error) { return j.Secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuer(issuer),
		jwt.WithExpirationRequired(),
		jwt.WithTimeFunc(func() time.Time { return now }),
	)
	if err != nil {
		return uuid.Nil, ErrInvalidToken
	}
	id, err := uuid.Parse(claims.Subject)
	if err != nil {
		return uuid.Nil, ErrInvalidToken
	}
	return id, nil
}

const shareAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I so codes are easy to read aloud

// ShareCode returns 8 characters from crypto/rand (32^8 ≈ 1.1e12 combinations).
func ShareCode() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	for i := range b {
		b[i] = shareAlphabet[int(b[i])%len(shareAlphabet)] // 256 % 32 == 0, so no bias
	}
	return string(b), nil
}
