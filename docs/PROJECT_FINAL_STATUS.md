# MailFlow — Final Project Status & Documentation

_Reflects the implementation after Gemini AI-assisted drafting was added. Figures are from actual local runs; nothing is fabricated._

---

## 1. Project overview & objective

A full-stack **email marketing & campaign automation platform**. Authenticated users import contacts from Excel/CSV, build personalized email templates, optionally polish drafts with AI, and send bulk campaigns. Sending is asynchronous: a Spring Batch job streams recipients into RabbitMQ, and a worker delivers each email over SMTP with Redis-backed rate limiting and idempotency.

**Pipeline:**
```
Excel/CSV upload → validation + dedup → PostgreSQL → Campaign
  → Spring Batch (paged reader) → RabbitMQ → EmailConsumer → SMTP → MailHog (dev) | Gmail (prod)
Redis: rate limiting + idempotency + caching
AI (optional, drafting only): React → /api/ai/improve-email → Gemini → human review (NOT in send path)
```

---

## 2. Technology stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.2, Spring Batch 5, Spring Security 6, Spring Data JPA, WebFlux (WebClient for AI) |
| Database | PostgreSQL 17, Flyway |
| Messaging | RabbitMQ 3.12 (Erlang/OTP 26) |
| Cache / rate limit | Redis 7 |
| Email | Jakarta Mail, Freemarker, MailHog (dev), Gmail SMTP (prod) |
| AI | Google Gemini (Generative Language API) via WebClient; OpenAI alternative; NoOp fallback |
| Auth | JWT (jjwt), BCrypt (cost 12), RBAC |
| File processing | Apache POI (Excel), OpenCSV |
| Frontend | React 18, TypeScript, Vite, Tailwind, React Query, Recharts |
| Infra | Docker, Docker Compose, GitHub Actions CI |

---

## 3. Architecture & component responsibilities

- `auth/` — JWT login, users, roles (ADMIN, CAMPAIGN_MANAGER, VIEWER).
- `recipient/` — Excel/CSV import, validate-only preview, dedup, bulk create/reuse, CRUD.
- `template/` — email templates with `{{variable}}` merge fields (Freemarker).
- `campaign/` — campaign lifecycle (DRAFT → QUEUED → RUNNING → COMPLETED), sender identity.
- `batch/` — Spring Batch job: paged JPA reader (chunk 50) → processor (personalization) → writer (publish to RabbitMQ).
- `queue/` — RabbitMQ producer + `EmailConsumer` (idempotency, rate limit, retry, SMTP send).
- `email/` — `EmailProvider`/`SmtpEmailProvider`, provider config, startup SMTP validation.
- `ai/` — `AiContentProvider` abstraction with `GeminiContentProvider`, `OpenAiContentProvider`, `NoOpAiContentProvider`; `AiConfig` selects the active provider; `AiController` exposes endpoints. **Drafting only — not part of the bulk send path.**
- `analytics/` — dashboard + per-campaign stats; `EmailLog`.
- `config/` — Redis, RabbitMQ, CORS (env-driven `FRONTEND_URL`), OpenAPI, `MailFlowProperties`.
- `scheduler/` — scheduled campaign launch.

---

## 4. Database schema & migrations

Flyway migrations **V1–V4**, applied cleanly to a fresh DB and verified against the running database (`ddl-auto=validate`):
- V1 init schema, V2 Spring Batch tables, V3 seed data (3 demo users + roles + templates + sample recipients), V4 campaign target join tables.
Role/phone/location/interest personalization values live in `recipients.custom_fields` (JSONB) — no schema change was needed for bulk-from-Excel. **No AI-related schema changes** were required (AI is stateless drafting).

---

## 5. Features implemented

Auth & RBAC · Excel/CSV import with validate-only preview · validation & dedup · `{{Name}}`/`{{FirstName}}`/`{{LastName}}`/`{{Company}}`/`{{Role}}`/`{{Phone}}`/`{{Location}}`/`{{Interest}}`/`{{Email}}` personalization · campaign lifecycle · async bulk sending (Batch→RabbitMQ→SMTP) · Redis rate limiting + idempotency · retry/backoff · Excel template download · dashboard with real stats · Gmail/MailHog SMTP (env-driven, startup validation) · **AI-assisted drafting (Gemini, optional)** · Swagger UI.

---

## 6. AI-assisted drafting — architecture, API, config, limitations

**Approach:** Option A — AI-assisted drafting with human review. The AI improves an existing draft's subject + body; a human reviews/edits before use. It is **never** invoked per-recipient during bulk sending, so the email pipeline is unchanged.

**Backend:**
- `AiContentProvider` interface: `generate(prompt)`, `improve(ImproveEmailRequest)`, `isAvailable()`.
- `GeminiContentProvider` — calls `{baseUrl}/models/{model}:generateContent?key=…` with `responseMimeType=application/json`, bounded timeout; maps errors (429 → 503 rate-limited, 400/401/403 → 503 auth, other → 502, timeout → 504); never logs the body or key.
- `OpenAiContentProvider` — alternative provider (chat completions).
- `NoOpAiContentProvider` — used when disabled / no key; throws a clean 503.
- `AiConfig` selects provider: `gemini` (if `GEMINI_API_KEY` set) → `openai` (if `OPENAI_API_KEY` set) → NoOp.
- `AiPromptBuilder` — shared system prompt with guardrails: preserve meaning, facts, links, merge variables and CTAs; do not invent recipient details/offers.

**API (both require ADMIN or CAMPAIGN_MANAGER):**
- `GET /api/ai/status` → `{available, message}`.
- `POST /api/ai/improve-email` → body `{subject*, body*, purpose?, audience?, tone?, keyPoints?}`, returns `{subject, body}`. Input validated (`@NotBlank`, size limits).
- `POST /api/ai/generate` (pre-existing free-form generation) also routed through the active provider.

**Config (env-driven, off by default):** `AI_ENABLED`, `AI_PROVIDER` (gemini|openai), `GEMINI_API_KEY`, `GEMINI_MODEL` (default `gemini-1.5-flash`), `GEMINI_BASE_URL`, `GEMINI_TIMEOUT_SECONDS`. Key is read only from the environment — never hardcoded, logged, returned to the frontend, or committed.

**Frontend:** `api/ai.ts` + `AiImproveModal.tsx`, wired into `CampaignFormModal` via an "Improve with AI" button by the subject field. Tone chips (professional/friendly/persuasive/concise), optional purpose/audience/key-points, loading state, editable review step, accept/discard. No auto-send.

**Limitations / honest status:**
- **Live Gemini authentication has NOT been verified** with a real key in this environment. Automated tests use a mocked provider; the disabled-path (503) was verified live. Supply a rotated `GEMINI_API_KEY` locally to use it for real.
- AI is drafting-only by design (no per-recipient generation at send time).

---

## 7. API endpoint inventory (from code)

- Auth: `POST /api/auth/register`, `/login`, `/refresh`.
- Recipients: `GET/POST /api/recipients`, `PUT/DELETE /api/recipients/{id}`, `POST /api/recipients/import/csv`, `/import/excel`, `/import/excel/preview`, `POST /api/recipients/bulk`, `GET /api/recipients/template/excel`.
- Templates: `GET /api/templates`, `POST /api/templates`, …
- Campaigns: `POST /api/campaigns`, `GET /api/campaigns`, `GET /api/campaigns/{id}`, `PUT /api/campaigns/{id}`, `POST /api/campaigns/{id}/launch|pause|resume|cancel`.
- Analytics: `GET /api/analytics/dashboard`, `/campaigns/{id}`, `/volume/daily`.
- AI: `GET /api/ai/status`, `POST /api/ai/improve-email`, `POST /api/ai/generate`.
- Docs: `GET /swagger-ui.html`.

---

## 8. Testing methodology & actual results

- **Backend unit/integration:** `mvn clean package` → **BUILD SUCCESS, 63 tests pass** (0 failures/errors). Includes 13 new AI tests: `GeminiContentProviderTest` (success, blank-input rejection, field fallback, 429/403 mapping, malformed response, availability) and `AiConfigTest` (provider selection gemini/openai/noop, disabled fallback, NoOp 503) — all using a **stubbed WebClient**, no network and no real key.
- **Frontend:** `npx tsc --noEmit` → no errors; `npm run build` → success.
- **End-to-end (local, MailHog, no real emails):** 100 recipients (+1 duplicate, +1 invalid). Preview 100 valid / 1 dup / 1 invalid; imported 100 (dedup reused, invalid rejected); campaign COMPLETED; **sent=100, failed=0, skipped=0**; 100 messages in MailHog; 100 distinct personalized subjects; 0 unresolved `{{ }}` placeholders (subject + body). AI: `/status available=false` and `improve-email → 503` with AI enabled but no key (safe optional behavior). The AI change did **not** break import, dedup, batch (>50), Redis, RabbitMQ, or SMTP.
- No real marketing email was sent to any external recipient.

---

## 9. Git / GitHub status

- Local branch `main`. Commits before this task: `a5fffdf`, `d73e72c` (both **not yet pushed** — remote empty).
- The AI integration changes are staged to be committed next (task 8) and pushed.
- **Push blocker:** GitHub rejects HTTPS password auth; GitHub CLI device-login needs the **`workflow`** scope to push `.github/workflows/ci.yml`. The portable `gh` binary used earlier was removed from temp. Completing the push requires `gh` auth (device flow, browser authorization by the repo owner) with `repo`+`workflow` scopes, then `git push -u origin main`, verified via `git ls-remote origin`.

---

## 10. Current running services (verified this session)

| Service | Port | Status |
|---|---|---|
| PostgreSQL 17 | 5432 | UP |
| Redis | 6379 | UP |
| RabbitMQ | 5672 | UP |
| MailHog SMTP / UI | 1025 / 8025 | UP |
| Backend (Spring Boot, MailHog + AI-enabled/no-key) | 8080 | UP |
| Frontend (Vite) | 3000 | not started this round |

Database: 4/4 migrations applied, 3 demo users, ~300+ recipients and ~20 campaigns (seed + accumulated test runs; harmless demo data — `docker compose down -v` resets to seed).

---

## 11. How to run locally

```powershell
# Infra (native tooling used this session) OR: docker compose up postgres redis rabbitmq mailhog -d
# Backend (MailHog mode; add AI_ENABLED=true + GEMINI_API_KEY to enable AI):
$env:DB_URL="jdbc:postgresql://localhost:5432/mailflow"; $env:DB_USERNAME="mailflow"; $env:DB_PASSWORD="mailflow"
$env:REDIS_HOST="localhost"; $env:RABBITMQ_HOST="localhost"; $env:RABBITMQ_USERNAME="guest"; $env:RABBITMQ_PASSWORD="guest"
$env:SMTP_HOST="localhost"; $env:SMTP_PORT="1025"; $env:SMTP_AUTH="false"; $env:SMTP_TLS="false"; $env:EMAIL_MODE="production"
$env:JWT_SECRET="local-dev-jwt-secret-at-least-256-bits-long-for-hmac-sha256-signing-key"
java -jar target/mailflow-backend-1.0.0.jar
# Frontend:
cd frontend; npm install; npm run dev    # http://localhost:3000
```
Or the whole stack: `docker compose up --build`. Demo login: `admin@mailflow.local` / `Admin@123`.

---

## 12. Known issues / roadmap

- Live Gemini not verified with a real key (see §6).
- Push to GitHub pending `workflow`-scope authorization (see §9).
- `TemplatesPage` "New Template" button is not wired to a create form (pre-existing; templates are created via API/seed). Left as-is per scope.
- Deferred to future versions: open/click tracking, segmentation, A/B testing, automation workflows, additional providers (SES/SendGrid), per-recipient AI (intentionally excluded).

---

## 13. Security considerations

- No secrets committed: `.env`, `.env.*`, `*.key`, `*.pem`, `*.p12` git-ignored; `.env.example` has placeholders only; `.dockerignore` excludes `.env`.
- `GEMINI_API_KEY`, SMTP credentials and `JWT_SECRET` are env-only; never logged (AI provider logs status codes only, never bodies or keys).
- **Credentials pasted into the assistant chat earlier (a Google API key and Gmail passwords) must be rotated/revoked** — treat them as compromised. None are in the repository.
- Real email delivery stays disabled in tests (MailHog); no external deployment performed.
