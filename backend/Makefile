.PHONY: env ip up down clean logs ps test test-race lint smoke run deploy-check

# Create .env with this Mac's LAN IP and random secrets (keeps an existing .env).
env:
	@if [ -f .env ]; then echo ".env already exists"; else \
	  IP=$$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1); \
	  sed -e "s#192.168.1.10#$$IP#g" \
	      -e "s#change-me-postgres#$$(openssl rand -hex 16)#" \
	      -e "s#change-me-to-at-least-32-random-characters#$$(openssl rand -hex 32)#" \
	      -e "s#change-me-s3-secret#$$(openssl rand -hex 16)#" .env.example > .env; \
	  echo "wrote .env (HOST_IP=$$IP)"; fi

# This Mac joined another Wi-Fi: point HOST_IP, PUBLIC_BASE_URL and S3_PUBLIC_ENDPOINT in .env at the new IP.
# Secrets are left untouched. Run `make up` afterwards so the api container picks it up.
ip:
	@test -f .env || (echo "no .env yet: run make env" && exit 1)
	@IP=$$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1); \
	  sed -i '' -E -e "s#^HOST_IP=.*#HOST_IP=$$IP#" \
	      -e "s#^PUBLIC_BASE_URL=http://[^:/]+:#PUBLIC_BASE_URL=http://$$IP:#" \
	      -e "s#^S3_PUBLIC_ENDPOINT=http://[^:/]+:#S3_PUBLIC_ENDPOINT=http://$$IP:#" .env; \
	  echo "HOST_IP=$$IP written to .env — now run: make up"

up:
	docker compose up -d --build
	@echo "API: http://localhost:8080/readyz"

down:
	docker compose down

clean:
	docker compose down -v

logs:
	docker compose logs -f api

ps:
	docker compose ps

# Integration tests start their own Postgres with testcontainers (Docker must be running).
test:
	go test -count=1 ./...

test-race:
	go test -race -count=1 ./...

lint:
	gofmt -l . | (! grep .) && go vet ./...
	@command -v golangci-lint >/dev/null && golangci-lint run || echo "golangci-lint not installed, skipped"

smoke:
	./scripts/smoke.sh

run:
	set -a; . ./.env; set +a; DATABASE_URL=postgres://runtrail:$$POSTGRES_PASSWORD@localhost:5432/runtrail?sslmode=disable \
	  S3_ENDPOINT=http://localhost:9000 S3_PATH_STYLE=true go run ./cmd/api

# Check a deployed server (e.g. Render): waits for a sleeping free instance, then runs the full smoke test.
#   make deploy-check URL=https://runtrail-api.onrender.com
deploy-check:
	@test -n "$(URL)" || (echo "usage: make deploy-check URL=https://..." && exit 1)
	@echo "waking $(URL) (a free instance can take ~1 minute)..."
	@curl -sf --max-time 120 --retry 3 --retry-delay 5 "$(URL)/readyz" && echo
	./scripts/smoke.sh "$(URL)"
