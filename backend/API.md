# Backend handoff

The backend implements the audit and app contracts in `PLAN.md`. Mobile source remains owned by the frontend agent.

## Connection

- API port: **8080**.
- API explorer: `http://localhost:8080/docs`.
- Emulator base URL: `http://10.0.2.2:8080/`.
- Physical phone: use the backend laptop's hotspot/LAN address, or `adb reverse tcp:8080 tcp:8080` and `http://127.0.0.1:8080/` over USB.
- Login accounts: `admin`, `organizer`, `manager`, `player`, `viewer` at `@nascon.test`, password `Nascon@26`.
- Authenticate app GET/configuration routes with `Authorization: Bearer <token>`.
- Audit actions intentionally do not require a token, except `/audit/v1/rbac/*` probes, as required by the grading contract.

## Demo identifiers

| Resource | IDs |
|---|---|
| Main league | `league-alpha-1` |
| Tenant | `tenant-alpha` |
| Clubs | `club-alpha-1`, `club-alpha-2`, `demo-club-3`, `demo-club-4` |
| Squads, in club order | `demo-squad-1`, `demo-squad-2`, `demo-squad-3`, `demo-squad-4` |
| Fixtures | `demo-m1` to `demo-m6` |
| Completed / live / upcoming | m1-m2 completed; m3 in progress; m4-m6 upcoming |
| Main product | `prod-jersey-001` |
| Variants | `var-jersey-001-M`, `var-jersey-001-L` |
| Discount | `WELCOME10` |
| Safe example player to transfer | `demo-player-1-1` (Bilal), from `demo-squad-1` to `demo-squad-2` |
| Seeded primary player | `player-alpha-1`; the sole goalkeeper, so transferring him correctly fails minimum-position checks |

`POST /audit/v1/reset` restores this demo data and the five accounts. The active transfer window includes the server's current date.

## Scoring and offline integration

- `GET /api/v1/sports/{sport_id}` returns the full sport configuration. Generate scoring controls from `scoring_events` keys; do not hard-code event names.
- `GET /api/v1/matches/{match_id}/stream` is SSE with `event: update` and the complete match JSON. A committed event, status change, product pin or pinned inventory change produces an update. Three-second polling remains supported.
- Match event `base_version` is the current score response's `version`.
- Roster `base_version` is the number of entries returned by `/audit/v1/roster/history/{player_id}`.
- Queue payloads must match Section 5.14 exactly. Results preserve request-array order; `processed_order` records chronological execution order.
- Rejected offline actions return HTTP 200 with a per-action `error_code`; malformed envelopes return HTTP 400.
- The sync response has applied/rejected/duplicate results. Display rejected actions in plain language and retain the results before clearing the pending queue.

## Validation evidence

- 134 tests passed on Python 3.12/Linux inside the API image.
- All six reported backend areas have at least 96% line coverage; `COVERAGE.json` is generated from the actual test run.
- Actual PostgreSQL checkout: 20 simultaneous buyers, exactly one successful order, stock/reserved both zero afterward. Verified across two independent API processes as well.
- Actual SSE update received; receipt PDF present in MinIO; order event present in Redis Streams.
- Clean database volumes and `docker compose up -d --build` reached healthy API/database/cache/storage. This laptop's initial dependency build used an optional verified wheel cache; a cold grader build fetches the locked PyPI URLs directly.

## Salt before judging

Local verification used the public **test** salt `demo-salt`. Set your real team `DAY1_SALT` in the shell, recreate the API/worker, and reset demo data before judging. The real salt must never be committed. Existing signed history is intentionally never re-signed when configuration changes.

## Local October 18 management additions

All routes below require a Bearer token. Existing audit contracts remain unchanged.

| Route | Role and request |
|---|---|
| GET /api/v1/clubs | Tenant-visible club directory; admins see all |
| GET /api/v1/leagues/{id}/management | Admin or assigned organizer; includes team IDs |
| POST /api/v1/leagues | Admin; `{name,sport_id,teams,tenant_id?}`; returns created league, 201 |
| POST /api/v1/matches | Admin or assigned organizer; `{league_id,home_team_id,away_team_id,scheduled_at,venue,duration_minutes?}`; returns match, 201. UTC time uses `YYYY-MM-DDTHH:MM:SSZ`; validates teams, venue bookings, overlaps and rest rules |
| GET /api/v1/management/products | Admin or own club manager; includes club ownership and variants |
| POST /api/v1/products | Admin or own club manager; `{name,description?,category,club_id,variants:[{label,price_minor,stock,size?,color?}]}`; 201 |
| PATCH /api/v1/products/{id}/variants/{variant} | Admin or own club manager; `{stock?,price_minor?}`; serialized with checkout transactions |
| POST /api/v1/offline-sync | Authenticated counterpart of the audit route; checks scoring league or source-club ownership and roster actor identity before replay |

The management UI also uses the existing order list/status routes. Customers retain their purchase history; managers can fulfil eligible orders, and admins can manage all orders.

Local validation: 143 backend tests passed, four Android offline projection tests passed, and the debug APK built. Device signed in over the LAN without an ADB reverse mapping; SQLite v2 contained 47 persisted responses. Full physical offline/restart/reconnect retesting is still pending. The local APK installs as `com.nascon.league.local` to preserve the differently signed Day 1 app.

## Bundled artwork demo catalogue

`GET /api/v1/fixtures/highlights` requires authentication and returns at most one real fixture per configured sport, preferring live, then upcoming, then completed fixtures within visible tenants. The mobile Matches home uses this route for its All sports filter; league-specific fixtures retain their existing API and complete lists.

`demo_catalog.ensure` adds missing cricket/esports clubs, three sport leagues (basketball, cricket, esports), four fixtures, and duffel/bottle products. Startup upgrades existing demo databases additively; reset seeds the same records. Existing league edits, event logs and product stock are preserved. New art is bundled in the APK and works without a network image service.

## SMTP receipt delivery

- `GET /api/v1/checkout/preferences` (authenticated): `{default_receipt_email,email_delivery_configured,delivery_mode}`. Local mode accepts demo `.test` accounts, and `DEMO_RECEIPT_EMAIL` may override their address label.
- `POST /api/v1/checkout` (authenticated): the audit checkout fields plus optional `receipt_email`. `customer_id` must match the token, and every variant must be in an accessible tenant. Omitted email uses the account preference; an explicit empty string opts out. Invalid/header-injected addresses are rejected before payment; external delivery also rejects `.test` addresses. The audit route and its exact response remain unchanged.
- App checkout adds `receipt_delivery: {email,status,sent_at,mode}` to the ordinary signed receipt response. One durable job is committed only on successful payment. Reusing an order ID never purchases or queues mail twice; changing its recipient returns a conflict.
- `GET /api/v1/orders/{id}/receipt-email` returns delivery status to the buying account or platform admin. States: `not_requested`, `queued`, `sending`, `waiting_configuration`, `retrying`, `sent`, `failed`.
- SMTP uses STARTTLS or implicit TLS with certificate validation, optional authentication and a bounded timeout. `none` is only for local test servers. The worker never holds the checkout DB lock during SMTP network I/O. Retries use backoff and a stable Message-ID; provider acceptance followed by a process crash can still produce a duplicate delivery.
- Default Compose delivery is local: `SMTP_DELIVERY_MODE=local`, `SMTP_HOST=local-mail`, `SMTP_PORT=1025`, `SMTP_FROM=receipts@arenaone.local`, empty credentials and `SMTP_TLS_MODE=none`. Captures stay in the Postgres volume; no external delivery is attempted. External SMTP is optional and requires explicitly changing mode, host, TLS and provider settings in `.env`.

### Local inbox routes

- `GET /local-mail`: offline-capable browser UI assets, using ArenaOne sign-in (not an email provider login).
- `GET /api/v1/mailbox`: authenticated message summaries belonging to the buyer; platform admins can see all.
- `GET /api/v1/mailbox/{message_id}`: authorized plain text message details.
- `GET /api/v1/mailbox/{message_id}/receipt.pdf`: authorized original PDF attachment.

The internal SMTP sink accepts only PDFs matching a paid order and its recorded recipient. Retries deduplicate by order and recipient even if MIME boundaries change. It exposes no SMTP host port, does not relay messages, and persists each capture before acknowledging SMTP acceptance. Browser message content is rendered as text.

## Match reminder metadata

App match detail and SSE messages include `scheduled_at` and `venue`. Android stores selected reminders locally per server/account/match and schedules a precise native alarm at kickoff minus ten minutes. No Firebase, cloud notification service, or backend reminder subscription is required. Reminder preference and OS alarm permissions stay local to each phone.
