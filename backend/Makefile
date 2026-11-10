.PHONY: env up down clean logs ps test test-race lint smoke run

# Create .env with this Mac's LAN IP and random secrets (keeps an existing .env).
env:
	@if [ -f .env ]; then echo ".env already exists"; else \
	  IP=$$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1); \
	  sed -e "s#192.168.1.10#$$IP#g" \
	      -e "s#change-me-postgres#$$(openssl rand -hex 16)#" \
	      -e "s#change-me-to-at-least-32-random-characters#$$(openssl rand -hex 32)#" \
	      -e "s#change-me-s3-secret#$$(openssl rand -hex 16)#" .env.example > .env; \
	  echo "wrote .env (HOST_IP=$$IP)"; fi

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
