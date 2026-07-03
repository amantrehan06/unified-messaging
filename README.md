# Unified Messaging

A multi-tenant AI inbox SaaS for small businesses.
Unify WhatsApp, SMS, and email into one inbox - AI drafts replies, the human stays in control.

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.5.x, Maven |
| Database | Neon (serverless Postgres 17) |
| Frontend | React + Vite + TypeScript |
| Compute | Google Cloud Run |
| CI | GitHub Actions (red blocks merge) |
| Integration tests | Testcontainers (real Postgres) |

## Local Development

### Prerequisites

- Java 21
- Node.js LTS
- Docker (for Testcontainers)
- Neon account with credentials in `backend/.env`

### Run full stack

```bash
./verify.sh
```

This compiles both projects, starts the backend on `:8080` and frontend on `:5173`, and verifies the health endpoint.
Ctrl+C stops both servers.

### Run individually

```bash
# Backend
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Frontend
cd frontend
npm run dev
```

### Run tests

```bash
# Backend (Docker must be running for Testcontainers)
cd backend
mvn test

# Frontend
cd frontend
npm run build
```

## Milestones

- **M0: Project Setup & CI** - Skeleton monorepo, test harness, CI gate. (current)
- M1+: Domain schema, ingestion, AI pipeline, and beyond.

## CI

GitHub Actions runs backend and frontend jobs in parallel on every push.
Branch protection requires CI to pass before merging to `main` - this gate is absolute.
