package auth

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestPasswordRoundTrip(t *testing.T) {
	h, err := HashPassword("correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$") {
		t.Fatalf("unexpected format %s", h)
	}
	if ok, _ := VerifyPassword("correct horse", h); !ok {
		t.Fatal("right password rejected")
	}
	if ok, _ := VerifyPassword("wrong", h); ok {
		t.Fatal("wrong password accepted")
	}
	h2, _ := HashPassword("correct horse")
	if h == h2 {
		t.Fatal("salt not random")
	}
	if _, err := VerifyPassword("x", "garbage"); err == nil {
		t.Fatal("malformed hash should error")
	}
}

func TestJWT(t *testing.T) {
	j := JWT{Secret: []byte(strings.Repeat("k", 32)), TTL: 15 * time.Minute}
	id := uuid.New()
	now := time.Now()
	tok, exp, err := j.Sign(id, now)
	if err != nil || !exp.Equal(now.Add(15*time.Minute)) {
		t.Fatal(err, exp)
	}
	got, err := j.Parse(tok, now.Add(time.Minute))
	if err != nil || got != id {
		t.Fatalf("parse: %v %v", got, err)
	}
	if _, err := j.Parse(tok, now.Add(16*time.Minute)); err == nil {
		t.Fatal("expired token accepted")
	}
	other := JWT{Secret: []byte(strings.Repeat("x", 32)), TTL: time.Minute}
	if _, err := other.Parse(tok, now); err == nil {
		t.Fatal("token signed with another secret accepted")
	}
	// alg "none" must be refused
	if _, err := j.Parse("eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.", now); err == nil {
		t.Fatal("alg none accepted")
	}
}

func TestRefreshTokenHash(t *testing.T) {
	plain, hash, err := NewRefreshToken()
	if err != nil || len(plain) < 40 {
		t.Fatal(err, plain)
	}
	if string(HashToken(plain)) != string(hash) {
		t.Fatal("hash mismatch")
	}
}

func TestShareCode(t *testing.T) {
	seen := map[string]bool{}
	for range 1000 {
		c, err := ShareCode()
		if err != nil || len(c) != 8 {
			t.Fatal(err, c)
		}
		for _, r := range c {
			if !strings.ContainsRune(shareAlphabet, r) {
				t.Fatalf("bad char %q in %s", r, c)
			}
		}
		if seen[c] {
			t.Fatal("duplicate code in 1000 draws")
		}
		seen[c] = true
	}
}
