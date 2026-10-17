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
