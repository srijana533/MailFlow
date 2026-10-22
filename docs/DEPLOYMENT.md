# Deployment Guide

This document describes how to deploy MailFlow to a cloud environment. **Nothing here is auto-deployed** — these are the exact manual steps and the configuration the app needs. The project is deployment-ready in that every environment-specific value is externalized to environment variables and the build artifacts (backend JAR, frontend static bundle, Docker images) are produced by the existing build files.

> Status: the application has been verified **locally** (50 backend tests, frontend build, and a 100-recipient end-to-end send via MailHog). It has **not** been deployed to a live cloud URL by this project; the steps below are what you run to do that.

---

## What you need to provision (managed services)

MailFlow depends on four backing services. On free/low-cost tiers, common choices are:

| Dependency | Managed option (examples) | Notes |
|---|---|---|
| PostgreSQL | Render PostgreSQL, Railway, Supabase, Neon | Flyway creates the schema on first boot |
| Redis | Render Key Value, Upstash, Railway | Rate limiting + idempotency |
| RabbitMQ | CloudAMQP (free "Little Lemur" plan) | Managed AMQP broker |
| SMTP | Gmail App Password (free), or SendGrid/SES | See `docs/EMAIL_SMTP_SETUP.md` |

You create these accounts and instances manually; each gives you a host/port/credentials to plug into environment variables.

---

## Backend deployment (container)

The backend builds to a single Spring Boot JAR and a container via the root `Dockerfile` (multi-stage: Maven build → JRE runtime, non-root user, `MaxRAMPercentage` tuned for containers).

Any platform that runs a Docker image or a Java 21 JAR works (Render Web Service, Railway, Fly.io, a VM, etc.).

**Required environment variables (set in the platform's dashboard — never in the repo):**

```
# Database
DB_URL=jdbc:postgresql://<host>:<port>/<db>
DB_USERNAME=<user>
DB_PASSWORD=<secret>

# Redis
REDIS_HOST=<host>
REDIS_PORT=<port>
REDIS_PASSWORD=<secret-if-any>

# RabbitMQ (from CloudAMQP, split the amqps URL into parts)
RABBITMQ_HOST=<host>
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=<user>
RABBITMQ_PASSWORD=<secret>

# SMTP — Gmail for real delivery (see docs/EMAIL_SMTP_SETUP.md)
EMAIL_MODE=production
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_AUTH=true
SMTP_TLS=true
SMTP_SSL_TRUST=smtp.gmail.com
SMTP_USERNAME=<your-gmail-address>
SMTP_PASSWORD=<google-app-password>
EMAIL_FROM_ADDRESS=<your-gmail-address>
EMAIL_FROM_NAME=MailFlow

# AI-assisted drafting (optional; off by default)
# Only set these if you want the "Improve with AI" feature.
AI_ENABLED=false
AI_PROVIDER=gemini
GEMINI_API_KEY=<google-ai-studio-key-if-enabling-ai>
GEMINI_MODEL=gemini-1.5-flash

# Security
JWT_SECRET=<generate a long random value, min 256 bits>

# CORS — your deployed frontend origin
FRONTEND_URL=https://<your-frontend-domain>
BACKEND_URL=https://<your-backend-domain>

# Server
SERVER_PORT=8080
```

Generate a JWT secret with, for example: `openssl rand -base64 48`.

Build/run locally to confirm the image before pushing:

```bash
docker build -t mailflow-backend .
docker run -p 8080:8080 --env-file .env mailflow-backend
```

On a platform like Render, point a Web Service at the repo (Docker runtime), set the env vars above, and it builds from the `Dockerfile`.

---

## Frontend deployment (static site)

The frontend is a Vite build (`npm run build` → `frontend/dist/`), deployable as a static site on Vercel, Netlify, Render Static Site, Cloudflare Pages, etc.

Two ways to connect it to the API:

1. **Static host + absolute API URL** — set `VITE_API_URL=https://<your-backend-domain>` at **build time** (Vite inlines env vars during build), and ensure the backend `FRONTEND_URL` includes the frontend origin so CORS allows it.
2. **Container + nginx proxy** — use `frontend/Dockerfile` (serves the build via nginx and proxies `/api/` to the backend). Update `frontend/nginx.conf`'s `proxy_pass` to the deployed backend host.

For a static host, build settings are typically:
- Build command: `npm run build`
- Publish directory: `dist`
- Environment variable: `VITE_API_URL=https://<your-backend-domain>`

---

## Database migrations

Flyway runs automatically on backend startup and applies `V1`–`V4` to a fresh database (schema, Spring Batch tables, seed data, campaign target tables). No manual migration step is required — just point `DB_URL` at an empty database. The seed migration (`V3`) inserts demo users/templates idempotently; change the demo passwords before any real use.

---

## Post-deploy smoke test

After the backend is up:

1. `GET https://<backend>/swagger-ui.html` loads.
2. `POST https://<backend>/api/auth/login` with a demo account returns a token.
3. Open the frontend, log in, upload a few recipients, create and launch a small campaign.
4. If using Gmail SMTP, confirm the test recipient actually received the email. (Do a 1–2 recipient test first; mind Gmail's daily sending limits.)

---

## Expected public URLs (fill in after you deploy)

These are placeholders — replace with your real URLs once deployed. Do not treat them as live until you have verified them.

- Frontend: `https://<your-frontend-domain>`
- Backend/API: `https://<your-backend-domain>`
- Swagger: `https://<your-backend-domain>/swagger-ui.html`

---

## Manual actions you must perform (summary)

The following cannot be automated from the repo and require your accounts/credentials:

1. Create managed PostgreSQL, Redis, and RabbitMQ instances; collect their connection details.
2. Create a Gmail App Password (2-Step Verification required) — or set up SendGrid/SES.
3. Generate a production `JWT_SECRET`.
4. Create the backend service on your platform, set all env vars above.
5. Create the frontend static site, set `VITE_API_URL`, and set the backend's `FRONTEND_URL` to the frontend origin.
6. Run the post-deploy smoke test and confirm the live URLs.
