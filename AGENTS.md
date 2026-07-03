# Project agent instructions

Repo-specific instructions for this monorepo. These layer on top of my global rules
in ~/.claude/CLAUDE.md and my architectural opinions in ~/OPINIONS.md. Where this file
and the global rules disagree, this file wins for this repo.

CLAUDE.md in the repo root is a symlink to this file.

## Stack

- Backend: Java 21, Spring Boot 3.5.16, Maven
- Database: Neon (Postgres 17), Testcontainers for tests
- Frontend: React + Vite + TypeScript
- CI: GitHub Actions (backend + frontend jobs, red blocks merge)

## Build / test / run commands

### Backend (from `backend/`)

- Build: `mvn -B package -DskipTests`
- Test: `mvn test` (requires Docker for Testcontainers)
- Run locally: `mvn spring-boot:run -Dspring-boot.run.profiles=local`
- Single test: `mvn test -pl backend -Dtest=TestClassName`

### Frontend (from `frontend/`)

- Install: `npm install`
- Dev server: `npm run dev`
- Build (type-check + bundle): `npm run build`

### Infrastructure

- Database: Neon (credentials in `backend/.env`, gitignored)
- Full stack verify: `./verify.sh` (starts backend + frontend, checks health)
- Docker is only needed for Testcontainers (manages its own containers)

## Conventions

- Java package base: `com.messaging`
- Domain modules: shared, tenant, ingestion, processing, knowledge, conversation, outbound, web
- REST endpoints under `/api/`
- Frontend API base URL configured via `VITE_API_BASE_URL` env var

## Key decisions

- Java 21 is at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` - set JAVA_HOME before mvn commands
- Spring Boot 3.5.16 is EOL (June 30 2026) - bump to 4.x when starting a new milestone
- No local Postgres / no docker-compose - dev connects to Neon directly via `backend/.env` (spring-dotenv loads it)
- Testcontainers manages its own ephemeral Postgres for tests - no docker-compose needed
- Backend Dockerfile exists for Cloud Run deploy (deferred to M1)
- No auth, schema, Flyway, Pinecone, Pub/Sub yet - all deferred to later milestones
