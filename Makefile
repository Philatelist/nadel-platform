# One entry point for people and agents. Run `make help` for the list.
SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker-compose.yml

.PHONY: help install up down infra backend web mobile dev gen test check lint format format-check clean e2e-install e2e

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

install: ## Install client dependencies
	pnpm install --frozen-lockfile

infra: ## Start Postgres + PostGIS and Keycloak and wait until healthy
	$(COMPOSE) up -d --wait

down: ## Stop local infrastructure
	$(COMPOSE) down

backend: ## Run the backend API on :8080 (needs `make infra`)
	cd backend && ./mvnw spring-boot:run

web: ## Run the web app on :5173 (proxies /api to :8080)
	pnpm --filter web dev

mobile: ## Start the Expo dev server
	pnpm --filter mobile start

up: infra ## Start infrastructure, backend and web together (Ctrl+C stops backend and web)
	@trap 'kill 0' INT TERM; \
	(cd backend && ./mvnw -q spring-boot:run) & \
	pnpm --filter web dev & \
	wait

dev: up ## Alias for `make up`

gen: ## Regenerate the TS API client from api/openapi.yaml
	pnpm gen:api

test: ## Backend tests (Testcontainers, needs Docker), client typecheck and client unit tests
	cd backend && ./mvnw -B verify
	pnpm -r typecheck
	pnpm -r test

lint: ## Lint all client packages
	pnpm -r lint

format: ## Format client code and configs with Prettier
	pnpm format

format-check: ## Check formatting without changing files
	pnpm format:check

e2e-install: ## Install the Playwright browser (chromium) for the end-to-end specs
	pnpm --filter web e2e:install

# The e2e run uses its own database (E2E_DB) in the compose Postgres, created fresh and dropped afterwards,
# so the demo database `nadel` of `make up` is never touched. Keycloak keeps its own database.
E2E_DB ?= nadel_e2e

# Needs JDK 25 on PATH/JAVA_HOME (as the backend build does). Ports 8080 and 5173 must be free. Not part of `check`.
e2e: infra ## End-to-end specs: starts infra, backend jar and web, runs Playwright, stops them
	cd backend && ./mvnw -B -q -DskipTests package
	@set -e -m; mkdir -p backend/target; \
	pids=""; \
	psql_su() { docker compose -f infra/docker-compose.yml exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 "$$@"; }; \
	drop_db() { psql_su -q -c "DROP DATABASE IF EXISTS $(E2E_DB) WITH (FORCE)" >/dev/null; }; \
	cleanup() { trap - EXIT INT TERM; \
	  for p in $$pids; do kill -TERM -- -$$p 2>/dev/null || true; done; \
	  for i in 1 2 3 4 5 6 7 8 9 10; do \
	    lsof -nP -iTCP:8080 -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 1; done; \
	  for p in $$pids; do kill -KILL -- -$$p 2>/dev/null || true; done; \
	  sleep 1; \
	  drop_db || echo "warning: could not drop database $(E2E_DB)"; \
	  if lsof -nP -iTCP:8080 -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then \
	    echo "warning: port 8080 or 5173 is still in use after e2e cleanup"; fi; }; \
	trap cleanup EXIT INT TERM; \
	drop_db; \
	psql_su -q -c "CREATE DATABASE $(E2E_DB) OWNER nadel_owner"; \
	psql_su -q -d $(E2E_DB) -c "CREATE EXTENSION IF NOT EXISTS postgis; GRANT USAGE ON SCHEMA public TO nadel_app"; \
	NADEL_DB_URL=jdbc:postgresql://localhost:5432/$(E2E_DB) \
	java -jar backend/target/nadel-backend-*-SNAPSHOT.jar > backend/target/e2e-backend.log 2>&1 & pids="$$pids $$!"; \
	pnpm --filter web dev > backend/target/e2e-web.log 2>&1 & pids="$$pids $$!"; \
	wait_for() { for i in $$(seq 1 90); do \
	  curl -sf -o /dev/null "$$2" && return 0; sleep 2; done; \
	  echo "timeout waiting for $$1 ($$2); see backend/target/e2e-*.log"; return 1; }; \
	wait_for keycloak http://localhost:8180/realms/nadel/.well-known/openid-configuration; \
	wait_for backend http://localhost:8080/actuator/health/readiness; \
	wait_for web http://localhost:5173/; \
	pnpm --filter web test:e2e

check: gen format-check lint test ## Everything CI runs: contract, format, lint, tests

clean: ## Remove build outputs
	cd backend && ./mvnw -q clean
	rm -rf web/dist
