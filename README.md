# Enterprise Banking Platform (Portfolio)

Full-fledged enterprise-level banking software â€" built as a 10-part series.

## Tech Stack
- **Frontend:** TypeScript + Next.js 14 (App Router)
- **Backend:** Java 17 + Spring Boot 3 (REST API, JPA, Security, Validation)
- **Database:** PostgreSQL 16 (native local install - no Docker needed)
- **Dev:** VS Code, Maven Wrapper (no global Maven needed), Docker Compose (Part 2+)

## Monorepo Layout
```
project 1/
  frontend/   # Next.js + TypeScript
  backend/    # Spring Boot + Java
  db/         # Postgres init scripts
  docs/       # Architecture + 10-part roadmap + VS Code setup
  docker-compose.yml
```

## Quick Start (Part 1)
### Frontend
```powershell
Set-Location frontend
npm install
npm run dev
# -> http://localhost:3000
```

### Backend (needs Maven once â€" wrapper included after Part 1 setup, or install Maven)
```powershell
Set-Location backend
.\mvnw.cmd spring-boot:run
# -> http://localhost:8080/api/health  {"status":"UP"}
```

### Database (Part 2+ â€" requires Docker Desktop)
```powershell
docker compose up -d db
```

## 10-Part Series
See [docs/roadmap-10-parts.md](docs/roadmap-10-parts.md).

**Current status: Part 1 â€" Foundation & Monorepo Scaffold âœ...**
- Next.js frontend boots with health page
- Spring Boot backend skeleton with /api/health
- Postgres docker-compose + init script
- VS Code workspace config
- Docs + architecture decision records

## VS Code
Open this folder in VS Code. Install recommended extensions when prompted.
See [docs/vs-code-setup.md](docs/vs-code-setup.md).
