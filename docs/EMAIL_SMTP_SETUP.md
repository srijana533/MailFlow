# Email / SMTP Configuration

MailFlow sends email through its existing pipeline:

```
Excel/CSV upload → recipient validation → PostgreSQL → Campaign
  → Spring Batch → RabbitMQ → EmailConsumer → SMTP → (MailHog | Gmail)
```

SMTP is configured **entirely through environment variables**. Two tested
setups are supported with the same code path (`SmtpEmailProvider`):

| Setup | Use case | Auth | TLS |
|-------|----------|------|-----|
| **MailHog** | Local development / testing | no | no |
| **Gmail SMTP** | Real delivery (free) | yes | yes (STARTTLS) |

No Gmail credentials are stored in Java, `application.yml`, the frontend, or
Git. The app password lives only in your local `.env` (git-ignored) or your
deployment's secret store.

---

## Environment variables

| Variable | MailHog | Gmail | Notes |
|----------|---------|-------|-------|
| `SMTP_HOST` | `localhost` | `smtp.gmail.com` | SMTP server host |
| `SMTP_PORT` | `1025` | `587` | 587 = STARTTLS submission port |
| `SMTP_AUTH` | `false` | `true` | Enables SMTP authentication |
| `SMTP_TLS` | `false` | `true` | Enables **and requires** STARTTLS |
| `SMTP_USERNAME` | *(empty)* | your Gmail address | Required when `SMTP_AUTH=true` |
| `SMTP_PASSWORD` | *(empty)* | Google **App Password** | Required when `SMTP_AUTH=true` |
| `EMAIL_MODE` | `development` or `production` | `production` | `development` logs to console and never opens SMTP |
| `EMAIL_FROM_ADDRESS` | any | any | See "From address" below |
| `EMAIL_FROM_NAME` | any | any | Display name on the From header |

Optional tuning (defaults applied if unset):

| Variable | Default | Purpose |
|----------|---------|---------|
| `SMTP_SSL_TRUST` | *(empty)* | Set to `smtp.gmail.com` only if your JVM trust store rejects the TLS handshake |
| `SMTP_CONNECTION_TIMEOUT_MS` | `10000` | TCP connect timeout |
| `SMTP_READ_TIMEOUT_MS` | `10000` | Socket read timeout |
| `SMTP_WRITE_TIMEOUT_MS` | `10000` | Socket write timeout |

---

## Running with MailHog (local development)

MailHog captures mail locally — nothing leaves your machine.

```
SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_AUTH=false
SMTP_TLS=false
EMAIL_MODE=production   # routes through real SMTP → MailHog
```

> Setting `EMAIL_MODE=development` instead skips SMTP entirely and just logs
> each email to the console (handy when MailHog isn't running).

Open the MailHog web UI at <http://localhost:8025> to inspect captured messages.

---

## Running with Gmail SMTP (real, free delivery)

### 1. Enable 2-Step Verification

Google only issues App Passwords on accounts with **2-Step Verification**
enabled: <https://myaccount.google.com/security> → *2-Step Verification*.

### 2. Create a Google App Password

1. Go to <https://myaccount.google.com/apppasswords>.
2. Create a new app password (e.g. name it "MailFlow").
3. Copy the generated **16-character** password (no spaces).

This is **not** your normal Gmail password. Treat it like a secret.

### 3. Configure environment variables

Put these in your local `.env` (git-ignored) or your deployment secret store:

```
EMAIL_MODE=production
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_AUTH=true
SMTP_TLS=true
SMTP_USERNAME=your@gmail.com
SMTP_PASSWORD=your16charapppassword
EMAIL_FROM_NAME=Your Brand
EMAIL_FROM_ADDRESS=your@gmail.com
```

### 4. Start the backend

PowerShell (Windows) example:

```powershell
$env:EMAIL_MODE="production"
$env:SMTP_HOST="smtp.gmail.com"; $env:SMTP_PORT="587"
$env:SMTP_AUTH="true"; $env:SMTP_TLS="true"
$env:SMTP_USERNAME="your@gmail.com"
$env:SMTP_PASSWORD="your16charapppassword"
$env:EMAIL_FROM_NAME="Your Brand"; $env:EMAIL_FROM_ADDRESS="your@gmail.com"
# ...plus the usual DB/Redis/RabbitMQ/JWT vars...
java -jar target/mailflow-backend-1.0.0.jar
```

Gmail free-tier sending limits apply (roughly ~500 recipients/day for
consumer accounts). The existing rate limiter (`EMAIL_RATE_LIMIT`) still
governs per-minute throughput.

---

## From address and Gmail

Gmail refuses to send mail whose `From` is not the authenticated account, and
will otherwise rewrite it. To stay compatible, `SmtpEmailProvider` behaves as
follows when an authenticated username is configured (`SMTP_AUTH=true` with
`SMTP_USERNAME` set):

- **From** is forced to `SMTP_USERNAME` (the authenticated Gmail address).
- **Display name** still comes from the campaign sender name / `EMAIL_FROM_NAME`.
- **Reply-To** falls back to the campaign's configured sender address, so
  replies still reach the intended mailbox (an explicit campaign Reply-To
  always wins).

For MailHog / un-authenticated SMTP (`SMTP_AUTH=false`, no username) the
campaign's own sender address is used unchanged — existing behaviour.

---

## Startup validation (fail fast)

When `EMAIL_MODE=production` **and** `SMTP_AUTH=true`, the app validates the
SMTP configuration at startup (`SmtpConfigValidator`). If any of
`SMTP_HOST`, `SMTP_USERNAME`, or `SMTP_PASSWORD` is missing or blank, the
application **fails to start** with a clear message listing exactly which
variables are missing — instead of starting successfully and then silently
failing every email during a campaign.

MailHog / development configurations are never blocked by this check.

---

## Security

- **Never commit** the Google App Password (or any secret) to Git. `.env`,
  `.env.local`, and `.env.production` are already in `.gitignore`.
- The app password is read only from the environment; it is never written to
  logs. Startup logging masks the SMTP username.
- Rotate or revoke an app password at
  <https://myaccount.google.com/apppasswords> if it is ever exposed.

---

## What is NOT changed

RabbitMQ, Spring Batch, Redis, retry logic, rate limiting, idempotency,
personalization (`{{Name}}`, `{{FirstName}}`, `{{Company}}`, `{{Role}}`,
`{{Phone}}`, `{{Location}}`, `{{Interest}}`), Excel/CSV bulk import, and
campaign analytics are all untouched. Switching between MailHog and Gmail is
purely an environment-variable change — no code or API changes.
