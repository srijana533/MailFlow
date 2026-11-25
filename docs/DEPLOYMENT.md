# Deploying MailFlow (free tier)

MailFlow has two parts that are hosted separately:

| Part | Host | Why |
|---|---|---|
| Frontend (React) | **Vercel** | Static site; Vercel serves it from its CDN. |
| Backend (Spring Boot API + email worker) | **Render** | A long-running Java server with a RabbitMQ consumer and batch jobs. Vercel only runs short serverless functions, so it can't host this part. |

The backend needs four services. All have a free plan:

| Service | Provider | Used for |
|---|---|---|
| PostgreSQL | [Neon](https://neon.tech) | All data |
| Redis | [Upstash](https://upstash.com) | Rate limiting, de-duplication, cache |
| RabbitMQ | [CloudAMQP](https://www.cloudamqp.com) ("Little Lemur" plan) | Email send queue |
| SMTP | [Brevo](https://www.brevo.com) | Sending email (about 300 per day free) |

Do the steps in order: each one produces a value that a later one needs.

## Limits of the free plan

- **Render's free backend sleeps after 15 minutes without requests.** The next request wakes it, and this takes 1 to 3 minutes. While it sleeps, scheduled campaigns don't start and queued emails aren't sent. A paid Render instance (about $7/month) stays awake.
- **Uploaded attachments are lost** when Render restarts or redeploys the backend, because the free instance has no persistent disk. Contacts, templates and campaigns are in PostgreSQL and are kept.
- Brevo's free plan sends about **300 emails a day**.

---

## 1. PostgreSQL on Neon

1. Create a project. Any region works; one close to your Render region is best.
2. On the dashboard, open **Connection string** and choose **Java**/JDBC, or copy the parts separately.
3. Note these values:
   - `DB_URL` = `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `DB_USERNAME`, `DB_PASSWORD`

The backend creates all tables itself on first start (Flyway migrations V1–V8).

## 2. Redis on Upstash

1. Create a Redis database and turn **TLS** on.
2. Note `REDIS_HOST` (the endpoint without `rediss://` and without the port), and the **password**. The port is `6379`.

## 3. RabbitMQ on CloudAMQP

1. Create an instance on the free **Little Lemur** plan.
2. Open it. The **AMQP URL** looks like `amqps://USER:PASSWORD@HOST/VHOST`.
3. Note `RABBITMQ_HOST`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` and `RABBITMQ_VHOST` (on CloudAMQP the virtual host is usually the same as the user name). The port is `5671` with TLS.

## 4. Email on Brevo

1. **Senders & IP → Senders**: add and verify the address emails will come from, e.g. `noreply@yourdomain.com`. This is `EMAIL_FROM_ADDRESS`. For good deliverability, also authenticate your domain (SPF/DKIM) under **Domains**.
2. **SMTP & API → SMTP**: create an **SMTP key**. The **Login** shown there (like `1234abc@smtp-brevo.com`) is `SMTP_USERNAME`, and the key is `SMTP_PASSWORD`.

Every email is sent **from** `EMAIL_FROM_ADDRESS`, with the campaign's sender name. The campaign's own sender address becomes the **Reply-To**, so replies go to the company that sent the campaign. Brevo rejects senders it hasn't verified, which is why `EMAIL_FROM_POLICY=platform` is used.

## 5. Backend on Render

1. Push this repository to GitHub.
2. In Render: **New → Blueprint**, pick the repository. Render reads `render.yaml`.
3. Render asks for every value marked `sync: false`. Fill them in from steps 1–4. For now, enter a placeholder for `FRONTEND_URL` such as `https://example.vercel.app`; you'll change it in step 7. For `APP_BASE_URL`, enter the service's own URL: `https://mailflow-api.onrender.com`, or the name Render shows.
4. Create the Blueprint. The first build takes several minutes.
5. Check it: `https://<your-service>.onrender.com/actuator/health/liveness` should answer `{"status":"UP"}`.

If the deploy fails, open **Logs**. With the `prod` profile, the backend refuses to start on unsafe settings and names the variable to fix. For example, it requires `JWT_SECRET` (Render generates it), `FRONTEND_URL`, and `DEMO_ACCOUNTS_ENABLED=false`.

## 6. Frontend on Vercel

1. In Vercel: **Add New → Project**, import the same repository.
2. Set **Root Directory** to `frontend`. Vercel detects Vite; `frontend/vercel.json` sets the rest.
3. Add the environment variable `VITE_API_URL` = your Render URL, e.g. `https://mailflow-api.onrender.com` (no trailing slash).
4. Deploy, then note the site's address, e.g. `https://mailflow.vercel.app`.

## 7. Connect them

In Render, set `FRONTEND_URL` to the Vercel address from step 6 and save. Render redeploys. This allows the browser to call the API (CORS). To also allow Vercel preview deployments, add their pattern after a comma, e.g.
`https://mailflow.vercel.app,https://mailflow-*-yourteam.vercel.app`.

## 8. First use

1. Open the Vercel site and choose **Create your company workspace**. You become that company's Admin.
2. Add teammates on the **Team** page with a role (Admin, Campaign manager or Viewer) and a starting password.
3. Import contacts, create a campaign, and launch it.

Each company only sees its own contacts, groups, templates, campaigns, analytics and unsubscribe list. The demo accounts from local development are disabled on the deployment.

## Optional: AI-assisted drafting

Off by default. To turn on "Improve with AI", add these in Render: `AI_ENABLED=true`, `AI_PROVIDER=gemini`, `GEMINI_API_KEY=<key from Google AI Studio>`, and optionally `GEMINI_MODEL`. Without them the AI endpoints answer 503 and everything else works.

## Smoke test after deploying

1. `GET https://<backend>/actuator/health/liveness` returns `{"status":"UP"}`.
2. On the Vercel site, sign up a company, add one contact (your own address), and create and launch a campaign to it.
3. The campaign reaches **COMPLETED** and the email arrives. If it stays **QUEUED**, check the Render logs for the RabbitMQ connection. If it **FAILED**, check the logs for the SMTP error; an unverified `EMAIL_FROM_ADDRESS` is the usual cause.
4. Sign up a second company in a private window and check that it sees none of the first company's data.

---

## Environment variables added for deployment

| Variable | Purpose | Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | Production settings and start-up safety check | (none) |
| `DEMO_ACCOUNTS_ENABLED` | Allow the seeded demo accounts to sign in | `true` locally, `false` with `prod` |
| `API_DOCS_ENABLED` | Serve Swagger UI with `prod` | `false` |
| `PORT` | Port the host assigns (Render sets it) | falls back to `SERVER_PORT`, then 8080 |
| `DB_POOL_SIZE`, `DB_POOL_MIN_IDLE` | Database connections | `20`, `2` |
| `REDIS_SSL` | TLS to Redis | `false` |
| `RABBITMQ_SSL`, `RABBITMQ_VHOST` | TLS and virtual host for RabbitMQ | `false`, `/` |
| `EMAIL_FROM_POLICY` | `smtp-username` (Gmail) or `platform` (Brevo, SES) | `smtp-username` |
