# MailFlow — Email Marketing & Campaign Automation Platform

MailFlow is a full-stack email campaign platform that lets authenticated users import contacts from Excel/CSV, build personalized email templates, and send bulk campaigns reliably. Sending is fully asynchronous: a Spring Batch job streams recipients into a RabbitMQ queue, and a worker delivers each email over SMTP with Redis-backed rate limiting and idempotency so no recipient is emailed twice. It ships with a React + TypeScript dashboard, JWT auth with role-based access, and a one-command Docker Compose stack for local development.

---

## Table of Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Tech Stack](#tech-stack)
4. [Local Setup](#local-setup)
5. [Environment Variables](#environment-variables)
6. [Email: MailHog vs Gmail](#email-mailhog-vs-gmail)
7. [Demo Flow](#demo-flow)
8. [Demo Credentials](#demo-credentials)
9. [Testing](#testing)
10. [Project Structure](#project-structure)
11. [Roadmap](#roadmap)

---

## Features

- **Bulk Excel/CSV contact import** — upload `.xlsx`, `.xls`, or `.csv`, with a validate-only preview before anything is saved.
- **Recipient validation & deduplication** — invalid emails are flagged; duplicates (within the file and against existing records) are detected and collapsed.
- **Personalized email templates** — `{{Name}}`, `{{FirstName}}`, `{{Company}}`, `{{Role}}`, `{{Phone}}`, `{{Location}}`, `{{Interest}}` and more, with safe empty fallbacks for missing values.
- **Campaign lifecycle** — create a draft, attach recipients, launch, and track status (DRAFT → QUEUED → RUNNING → COMPLETED).
- **Asynchronous bulk sending** — Spring Batch reads recipients in pages and publishes to RabbitMQ; a consumer sends over SMTP.
- **Redis rate limiting & idempotency** — token-bucket limiting per campaign and a per-recipient idempotency key prevent duplicate delivery on retries/restarts.
- **Retry handling** — transient SMTP failures are retried with backoff; permanent failures are not retried.
- **Role-based access** — JWT auth with `ADMIN`, `CAMPAIGN_MANAGER`, and `VIEWER` roles.
- **Gmail SMTP integration** — real delivery via a Google App Password, configured entirely through environment variables; MailHog is the default for local/dev.
- **AI-assisted drafting (optional)** — a "Improve with AI" action that uses Google Gemini to polish an email draft's subject and body (tone, clarity, professionalism) while preserving meaning, facts, links and merge variables. Human reviews and edits before use; nothing is sent automatically. Fully env-driven and off by default.
- **Campaign tracking** — per-campaign sent/failed/skipped counts and a dashboard with real delivery stats.
- **API docs** — Swagger UI at `/swagger-ui.html`.

---

## Architecture

```
React + TypeScript (dashboard)
        │  REST + JWT
        ▼
Spring Boot API ──────────────► PostgreSQL   (users, recipients, campaigns, logs)
        │                               ▲
        │ launch campaign               │ status / counters
        ▼                               │
Spring Batch (paged reader) ────────────┘
        │  publishes EmailMessage
        ▼
RabbitMQ (email queue)
        │  consumed by
        ▼
Email Consumer ──► SMTP ──► MailHog (dev) | Gmail (prod)

Redis: token-bucket rate limiting + per-recipient idempotency keys + caching
```

**Why this shape:** decoupling batch processing from SMTP via a queue means a slow mail server applies backpressure to the queue instead of stalling the API; the consumer can be scaled independently; and Redis makes the rate limit and idempotency checks atomic across workers. Spring Batch streams recipients in chunks so a large list is never loaded fully into memory.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2, Spring Batch 5, Spring Security 6 |
| Database | PostgreSQL, Spring Data JPA, Hibernate, Flyway |
| Messaging | RabbitMQ |
| Caching / Rate Limiting | Redis |
| Email | Jakarta Mail, Freemarker templating |
| Auth | JWT (jjwt), BCrypt |
| File Processing | Apache POI (Excel), OpenCSV |
| API Docs | SpringDoc OpenAPI / Swagger UI |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, React Query, Recharts |
| Testing | JUnit 5, Mockito, Spring Boot Test |
| Infrastructure | Docker, Docker Compose, MailHog |
| CI | GitHub Actions |

---

## Local Setup

### Option A — Docker Compose (everything in one command)

Requires Docker Desktop.

```bash
git clone <your-repo-url> mailflow
cd mailflow
cp .env.example .env        # review values; defaults work for local dev
docker compose up --build
```

Services:

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| RabbitMQ management | http://localhost:15672 (guest / guest) |
| MailHog (captured emails) | http://localhost:8025 |

### Option B — Run infrastructure in Docker, apps locally

```bash
# Start only the backing services
docker compose up postgres redis rabbitmq mailhog -d

# Backend (Java 21 + Maven)
mvn spring-boot:run

# Frontend (Node 20)
cd frontend
npm install
npm run dev
```

The backend reads configuration from environment variables (see below); sensible local defaults are built in, so it runs against the Docker services with no extra setup.

---

## Environment Variables

Copy `.env.example` to `.env` and adjust as needed. **Never commit `.env` or any real secret.** The full list is in `.env.example`; the key variables:

| Variable | Purpose | Local default |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL connection | local Postgres |
| `REDIS_HOST` / `REDIS_PORT` | Redis | `localhost` / `6379` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | RabbitMQ | `localhost` / `5672` / `guest` / `guest` |
| `SMTP_HOST` / `SMTP_PORT` | SMTP server | `localhost` / `1025` (MailHog) |
| `SMTP_AUTH` / `SMTP_TLS` | SMTP auth + STARTTLS | `false` / `false` |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP credentials (Gmail) | empty for MailHog |
| `SMTP_SSL_TRUST` | TLS trust host (set to `smtp.gmail.com` for Gmail) | empty |
| `EMAIL_MODE` | `development` (log only) or `production` (real SMTP) | `development` |
| `EMAIL_FROM_ADDRESS` / `EMAIL_FROM_NAME` | Default sender identity | `noreply@mailflow.local` / `MailFlow` |
| `EMAIL_RATE_LIMIT` | Max emails per minute | `100` |
| `JWT_SECRET` | JWT signing key (min 256 bits) | ⚠️ change for any real deployment |
| `AI_ENABLED` / `AI_PROVIDER` | Enable AI drafting; `gemini` or `openai` | `false` / `gemini` |
| `GEMINI_API_KEY` / `GEMINI_MODEL` | Google Gemini key + model (for AI drafting) | empty / `gemini-1.5-flash` |
| `FRONTEND_URL` | Deployed frontend origin(s), added to CORS allow-list | `http://localhost:3000` |
| `BACKEND_URL` | Public API base URL | `http://localhost:8080` |

> **Secrets to never commit:** Gmail App Password, `GEMINI_API_KEY`, `JWT_SECRET` for production, database password, RabbitMQ production credentials, any API key. `.env`, `.env.*`, `*.key`, `*.pem`, and `*.p12` are already git-ignored.

---

## Email: MailHog vs Gmail

MailFlow uses the same SMTP code path for both; only environment variables change. Full guide: [`docs/EMAIL_SMTP_SETUP.md`](docs/EMAIL_SMTP_SETUP.md).

**Local / demo (MailHog — no real emails):**
```
SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_AUTH=false
SMTP_TLS=false
EMAIL_MODE=production   # routes through real SMTP → MailHog UI at :8025
```

**Real delivery (Gmail, free):**
```
EMAIL_MODE=production
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_AUTH=true
SMTP_TLS=true
SMTP_SSL_TRUST=smtp.gmail.com
SMTP_USERNAME=your@gmail.com
SMTP_PASSWORD=your-16-char-google-app-password
EMAIL_FROM_ADDRESS=your@gmail.com
```

Gmail requires **2-Step Verification** and a **Google App Password** (not your normal password). When authenticated SMTP is configured, the app validates the credentials are present at startup and fails fast with a clear message if any are missing.

---

## AI-assisted drafting (optional)

MailFlow can use **Google Gemini** to help polish an email draft before sending. It is **AI-assisted drafting with human review** — the model never sends anything and never generates per-recipient content during bulk sending.

**How it works:** in the campaign form, click **"Improve with AI"** next to the subject. Optionally add the campaign purpose, audience, tone (professional / friendly / persuasive / concise) and key points. The backend calls Gemini, returns an improved subject + body, and you **review and edit** before accepting. Merge variables like `{{Name}}`, `{{Company}}`, `{{Role}}` are preserved; the model is instructed not to invent recipient details, offers or facts.

**Enable it** by setting these environment variables (AI is off by default):

```
AI_ENABLED=true
AI_PROVIDER=gemini
GEMINI_API_KEY=<your-google-ai-studio-key>   # never commit this
GEMINI_MODEL=gemini-1.5-flash
```

Get a key from [Google AI Studio](https://aistudio.google.com/apikey). The key is read only from the environment — it is never hardcoded, logged, returned to the frontend, or committed.

**Endpoints:** `GET /api/ai/status` (is AI available?) and `POST /api/ai/improve-email` (`{subject, body, purpose?, audience?, tone?, keyPoints?}` → `{subject, body}`). Both require the `ADMIN` or `CAMPAIGN_MANAGER` role. When AI is disabled or no key is set, these return HTTP 503 with a clear message and the rest of MailFlow is unaffected.

**Verification status:** the integration is covered by automated tests using a **mocked** provider (no real API calls), and the disabled-path (503) was verified live. **Live Gemini authentication has not been verified** with a real key in this environment — supply a rotated `GEMINI_API_KEY` locally to use it for real.

---

## Demo Flow

A recruiter / reviewer can exercise the whole product in a few minutes:

1. **Log in** at http://localhost:3000 with the Admin demo account (buttons on the login page auto-fill it).
2. **Recipients** → upload a small `.csv`/`.xlsx` (columns: `Email, Name, FirstName, Company, Role, Location, Interest`). Download the template from the upload screen if needed.
3. **Preview** — see valid / invalid / duplicate counts before anything is saved; remove rows if desired.
4. **Import** the valid recipients.
5. **Campaigns** → create a campaign, pick a template, and use personalization variables like `{{FirstName}}` in the subject/body.
6. **Launch** the campaign.
7. Watch it process (Spring Batch → RabbitMQ → SMTP) and the status move to **COMPLETED**.
8. Open **MailHog** at http://localhost:8025 to see the personalized emails that were delivered.

---

## Demo Credentials

> Seeded for local development. **Change them before any real deployment.**

| Role | Email | Password |
|---|---|---|
| Admin | `admin@mailflow.local` | `Admin@123` |
| Campaign Manager | `manager@mailflow.local` | `Manager@123` |
| Viewer | `viewer@mailflow.local` | `Viewer@123` |

---

## Testing

### Automated tests

```bash
mvn clean test        # backend unit tests
cd frontend
npx tsc --noEmit      # frontend type check
npm run build         # frontend production build
```

Current results (verified locally):

- **Backend:** 63 tests pass (`mvn clean package` → BUILD SUCCESS). Covers auth, seed credentials, rate limiter, template engine, recipient import/preview, campaign service, batch personalization model, SMTP configuration/validation, and the AI drafting provider + provider selection.
- **Frontend:** `tsc --noEmit` passes with no type errors; `npm run build` produces a production bundle.
- SMTP and AI tests use mocks only — **no real emails are sent and no real AI API calls are made during tests.**

### End-to-end (local, verified via MailHog)

A full run with **100 recipients** (plus one duplicate and one invalid row to exercise validation):

| Metric | Result |
|---|---|
| Rows uploaded | 102 (100 valid, 1 duplicate, 1 invalid) |
| Preview classification | 100 valid / 1 duplicate / 1 invalid ✅ |
| Recipients imported | 100 (duplicate collapsed, invalid rejected) |
| Campaign status | COMPLETED |
| Sent | 100 |
| Failed | 0 |
| Skipped | 0 |
| Delivered to MailHog | 100 |
| Unresolved `{{ }}` placeholders | 0 |

Additionally, with `AI_ENABLED=true` but no API key, `GET /api/ai/status` returned `available=false` and `POST /api/ai/improve-email` returned HTTP 503 — confirming AI is optional and fails safely without breaking the pipeline.

These are the actual results observed on a local run against MailHog, not projected benchmarks. Throughput at larger scale depends on the SMTP provider's limits, the configured rate limit, and worker count.

---

## Project Structure

```
mailer/
├── src/main/java/com/mailflow/   # Spring Boot backend
│   ├── auth/                      # JWT auth, users, roles
│   ├── recipient/                # import, preview, dedup, CRUD
│   ├── campaign/                 # campaign lifecycle
│   ├── batch/                    # Spring Batch job (reader/processor/writer)
│   ├── queue/                    # RabbitMQ producer + EmailConsumer
│   ├── ai/                       # AI drafting (Gemini/OpenAI providers, controller)
│   ├── email/                    # EmailProvider (SMTP), config, validation
│   ├── template/                 # Freemarker personalization
│   ├── analytics/                # dashboard + campaign stats
│   └── config/                   # Redis, RabbitMQ, CORS, OpenAPI
├── src/main/resources/
│   ├── application.yml           # env-driven configuration
│   └── db/migration/             # Flyway V1–V4
├── frontend/                     # React + TypeScript + Vite + Tailwind
├── docs/EMAIL_SMTP_SETUP.md      # MailHog + Gmail SMTP guide
├── Dockerfile                    # backend (multi-stage)
├── frontend/Dockerfile           # frontend (build → nginx)
├── docker-compose.yml            # full local stack
└── .github/workflows/ci.yml      # CI: test + build
```

---

## Roadmap

Planned for later versions (intentionally **not** in V1):

- Open / click tracking
- Additional email providers (Amazon SES, SendGrid)
- Audience segmentation and automation workflows
- A/B testing
- Real-time campaign progress via WebSocket

---

> MailFlow is a portfolio project demonstrating asynchronous processing, message queues, batch jobs, and reliable email delivery. Demo credentials and defaults are for local development only.
