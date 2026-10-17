# 0010. Hosting: one EU virtual server with Docker Compose, deployed from GitHub Actions

**Status:** accepted

## Context
The first production user is a single cooperative (pilot), growing later to tens of cooperatives (roadmap phase 7). One
person runs the platform. The stack is a modular monolith (ADR 0001) with PostgreSQL + PostGIS, Keycloak and a static web
client. Data belongs to Ukrainian cooperatives and includes personal data of their members. Hosting inside Ukraine is
exposed to power and network outages. Managed Kubernetes or a multi-service cloud setup costs more and needs more
operational knowledge than one person can keep up with.

## Decision
- Production runs on one virtual server in an EU data centre (initially Hetzner Cloud, Germany or Finland), about
  4 vCPU and 8 GB RAM, with Docker Compose: PostgreSQL + PostGIS, Keycloak, the backend, and Caddy serving the web
  build and terminating TLS (automatic certificates). A smaller second server is the staging environment.
- Images are built by GitHub Actions and pushed to GitHub Container Registry, tagged with the commit SHA and, for
  releases, with the version. Every merge to `main` deploys to staging; a `v*` tag deploys to production after manual
  approval in the protected GitHub environment `production`. Deployment is `docker compose pull && docker compose up -d`
  over SSH.
- Database migrations run with Flyway at backend start (as today). Migrations follow expand/contract so the previous
  release still runs on the migrated schema; rollback is redeploying the previous tag.
- Nightly encrypted database backups go to storage outside the server; restore is tested monthly on staging.
- Secrets live only in the server's environment files and in GitHub environments.

## Consequences
- Low fixed cost (tens of euros per month) and a setup one person can understand end to end.
- A short downtime (seconds) on every production deploy, and no automatic failover: a server failure means restoring
  on a new server from backup. Acceptable for the pilot; revisit with the SaaS phase (managed database, second app
  instance) when the number of cooperatives or an availability promise requires it.
- The provider can be replaced by any host that runs Docker; nothing in the code depends on Hetzner.
- Cross-border storage of Ukrainian personal data in the EU must be covered by the privacy policy and the agreement
  with each cooperative (legal review before the pilot).
