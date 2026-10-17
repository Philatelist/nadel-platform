# One entry point for people and agents. Run `make help` for the list.
SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker-compose.yml

.PHONY: help install up down infra backend web mobile dev gen test check lint format format-check clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

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

test: ## Backend tests (Testcontainers, needs Docker) and client typecheck
	cd backend && ./mvnw -B verify
	pnpm -r typecheck

lint: ## Lint all client packages
	pnpm -r lint

format: ## Format client code and configs with Prettier
	pnpm format

format-check: ## Check formatting without changing files
	pnpm format:check

check: gen format-check lint test ## Everything CI runs: contract, format, lint, tests

clean: ## Remove build outputs
	cd backend && ./mvnw -q clean
	rm -rf web/dist
