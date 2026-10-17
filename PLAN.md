# PLAN — NaSCon '26 Day 1: Multi-Sport League & Club Ops Platform

Spec = the Part I PDF (binding). If this plan and the spec disagree, **the spec wins**.
Deadline: last push **before 15:05:00 PKT**. Push checkpoints (≥1 push each): 10:45–11:45, 11:45–12:45, 12:45–13:45, 13:45–14:45.

---

## 1. Who does what

| Owner | Folders / files (only touch your own) | Goal |
|---|---|---|
| **Agent F: Frontend (mobile)** | `mobile/` | Android app: 9 screens, offline cache + action queue, secure token storage, `mobile/release/app-release.apk` |
| **Agent B: Backend** | `backend/`, `docker-compose.yml`, `.env.example`, `tests/`, `run_tests.sh`, `COVERAGE.json` | All `/audit/v1/*` routes from spec §5, **plus the `/api/v1/*` app routes in §3 below** |
| Shared (edit small sections, pull first) | `README.md`, `TRACEABILITY.md`, `PLAN.md` | Docs |

Git rules for both agents:
- Commit only your own folders. `git pull` before `git push`. **Never force-push, amend pushed commits, or rebase pushed commits.**
- Push after every working piece, at least once per checkpoint window.
- Nothing is pushed after 15:05.

---

## 2. Backend (Agent B): key points from the spec

- The API runs on **host port 8080**: `ports: ["8080:8080"]`. Compose passes `DAY1_SALT: ${DAY1_SALT}`. The salt comes from the env only and is never committed.
- `docker compose up -d --build` must work on 4 vCPU / 8 GB within 10 min, and `/health` must return 200 within 120 s. There must be no manual steps. Seed everything on startup and on `POST /audit/v1/reset`.
- Every non-2xx response uses this body: `{"error":{"code":"UPPER_SNAKE","message":"...","details":{}}}`. Override FastAPI's 422 into **400 VALIDATION_ERROR** with `details.fields`. Bodies over 1 MB get **413 PAYLOAD_TOO_LARGE**.
- Validation order: 400, then 404, then 409/402. The first failure wins.
- **No `if sport == "football"` and no `if event == "goal"` anywhere** (that's a −8 penalty). Everything comes from the sport JSON config.
- HMAC signing uses the exact recipe in spec §5.3 / Appendix A (`json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False)`). Check it against test vector 1.
- Checkout must be concurrency-safe: `SELECT ... FOR UPDATE` on the variant row, or an atomic `UPDATE ... SET stock = stock - q WHERE stock >= q`.
- Rate limiting applies **only** to `/api/v1/*`: 100 requests per rolling 10 s per IP, then 429 RATE_LIMITED with a `Retry-After` header. `/audit/v1/*` and `/health` are exempt.
- Suggested order:
  1. Skeleton: compose, `/health`, the error handler, `sign()`.
  2. `/reset` and seed data.
  3. Sports, events and score.
  4. Standings.
  5. Checkout and inventory.
  6. RBAC and ping.
  7. Roster.
  8. Fixtures (all 4).
  9. Offline-sync.
  10. **§3 app routes** (login + lists): do these **early, right after step 2**, so the app can connect.

---

## 3. App ⇄ Backend contract (`/api/v1/*`), which Agent B implements

All routes send and receive JSON. `Authorization: Bearer <jwt>` comes from login, and the error body is the same as above. These routes are rate-limited (100 requests per 10 s), so **the app must not poll faster than every 3 s**.

### 3.1 Auth
`POST /api/v1/auth/login` with body `{"email":"manager@nascon.test","password":"Nascon@26"}`
- **200:**
```json
{ "token": "<jwt>",
  "user": { "user_id": "u-manager", "email": "manager@nascon.test", "name": "Club Manager",
            "role": "club_manager", "tenant_id": "tenant-alpha",
            "league_id": null, "club_id": "club-alpha-1", "player_id": null },
  "tenant": { "tenant_id": "tenant-alpha", "name": "Alpha Sports",
              "primary_color": "#1565C0", "logo_url": null } }
```
- **401** `INVALID_CREDENTIALS` for a wrong email or password.
- `role` is one of `super_admin`, `league_organizer`, `club_manager`, `player`, `viewer`.

`GET /api/v1/me` returns the same `user` and `tenant` objects.

Demo accounts (spec §8.3; password `Nascon@26` for all):

| Email | Role | Bound to |
|---|---|---|
| admin@nascon.test | super_admin | (platform-wide) |
| organizer@nascon.test | league_organizer | league-alpha-1 |
| manager@nascon.test | club_manager | club-alpha-1 |
| player@nascon.test | player | player-alpha-1 |
| viewer@nascon.test | viewer | tenant-alpha |

### 3.2 Lists for the app screens
| Route | Response 200 |
|---|---|
| `GET /api/v1/leagues` | `[{"league_id","name","sport_id","tenant_id"}]` (only the caller's tenant; super_admin sees all) |
| `GET /api/v1/fixtures?league_id=league-alpha-1` | `[{"match_id","league_id","sport_id","home_team_id","home_name","away_team_id","away_name","status","scheduled_at","venue","home_score","away_score"}]` |
| `GET /api/v1/matches/{match_id}` | Same body as `GET /audit/v1/score/{match_id}`, **plus** `"pinned_product": {product} or null` |
| `GET /api/v1/matches/{match_id}/stream` | **SSE** (`text/event-stream`). Sends `event: update` with the match JSON on every new event, status change or product pin. The app falls back to polling every 3 s if SSE fails. |
| `GET /api/v1/standings/{league_id}` | Same body as `GET /audit/v1/standings/{league_id}` |
| `GET /api/v1/clubs/{club_id}` | `{"club_id","name","tenant_id","home_venue":{"name","lat","lng"},"socials":{"x","instagram"},"staff":[{"name","role"}],"roster":[{"player_id","name","position","role","squad_id"}],"stats":[{"season","played","won","drawn","lost"}]}` |
| `GET /api/v1/products` | `[{"product_id","name","category","description","variants":[{"variant_id","label","size","color","price_minor","currency","stock"}]}]` |
| `POST /api/v1/matches/{match_id}/pin` with body `{"product_id"}` | 200 with the match JSON; pushes an SSE update (organizer/admin only) |
| `GET /api/v1/ping` | `{"pong":true}` (the rate-limit probe in the spec) |

### 3.3 Actions (the app calls the spec routes directly, sending its Bearer token as well)
| Action in app | Route | Body |
|---|---|---|
| Checkout | `POST /audit/v1/checkout` | spec §5.12 (`customer_id` = `user.user_id`; `order_id` = random UUID generated by the app) |
| Score event / start / end / undo | `POST /audit/v1/events` | spec §5.9 (`event_id` = random UUID; undo = `type:"compensation"`) |
| Transfer / loan / release | `POST /audit/v1/roster/transfer` | spec §5.11 (`request_id` = random UUID, `league_id` = `league-alpha-1`) |
| Player history | `GET /audit/v1/roster/history/{player_id}` | (none) |
| Sync offline queue | `POST /audit/v1/offline-sync` | spec §5.14 (`device_id` = stable per install) |

### 3.4 Demo content seeded by `/reset`, so the app has something to show
Use **`demo-` prefixed ids** so they never collide with grader ids such as `m1` or `lg-1`.
- League `league-alpha-1` (football, tenant-alpha) is registered for standings. Its teams are `club-alpha-1`, `club-alpha-2`, `demo-club-3` and `demo-club-4`, and it has 6 fixtures `demo-m1`…`demo-m6`. Two are completed with events, one is in_progress and the rest are upcoming. `scheduled_at` and `venue` are filled in.
- The roster league for `league-alpha-1` has a squad per club with about 5 players each (including `player-alpha-1` in club-alpha-1). The transfer window must include today.
- The club profile data (venue, socials, staff, stats) for every club.
- Product `prod-jersey-001` with variants M and L, plus 1–2 extra demo products.

---

## 4. Frontend (Agent F): mobile plan

Stack: Kotlin + Compose (pinned versions in `mobile/gradle/libs.versions.toml`), MVVM (ViewModel + StateFlow), Retrofit + kotlinx-serialization.
- **Local DB:** raw `SQLiteOpenHelper`, not Room. That avoids pulling in KSP.
- **Token storage:** an AES key in the Android Keystore encrypts the JWT, which is then saved in SharedPreferences.
- **Server URL:** editable in Settings; the default comes from `BuildConfig.API_BASE_URL`.
- **Test tags:** every interactive element gets `testTag`, and the root sets `testTagsAsResourceId`.

### Screens, in build order
| # | Screen | Notes | Status |
|---|---|---|---|
| 0 | App shell | Theme, tenant colour, top **Online / Offline / Syncing** bar (always visible), bottom navigation: Fixtures, Standings, Store, Profile | Todo |
| 1 | Login | Email + password, demo-account quick-pick, server URL setting, error messages in plain language | Todo |
| 2 | Fixtures → Match detail | List with status chips and time. Detail shows a big score, event timeline (compensated events struck through), pinned product and live updates over SSE (polling fallback). | Todo |
| 3 | Standings | League picker, table with Pos/Team/P/W/D/L/GD/Pts and a status label (Clinched / Eliminated / In contention), shown as text and an icon, not colour only | Todo |
| 4 | Store | Catalogue, variant picker, cart, discount code, pay (test-card choice: success / declined / capture-fail), receipt screen, open/share the PDF | Todo |
| 5 | Club profile | Info, venue, socials, staff, roster | Todo |
| 6 | Roster management (Club Manager only) | Transfer / loan / release form, confirmation dialog, result message, player history | Todo |
| 7 | Scoring entry (Organizer only) | Start match, score buttons generated **from the sport config** (no hard-coded names), discipline, end match, undo with a reason | Todo |
| 8 | Sync status | Pending queue list, sync button, per-action result (applied / rejected + plain-language reason) | Todo |

### Offline (NFR-4, desk check §8.4)
- Cache every GET response (fixtures, standings, club, products, matches) in SQLite as JSON keyed by URL. When offline, show the cached data and an "Offline" banner.
- Scoring and roster actions taken offline are saved to the `pending_actions` table in the exact §5.14 format (`action_id`, `type`, `queued_at`, `base_version`, `payload`). They survive the app being killed.
- When the network comes back, `ConnectivityManager` triggers an automatic sync to `/audit/v1/offline-sync`. The app shows each result, translates error codes into plain English and clears the queue.

### Role → what the user sees
| Role | Fixtures | Standings | Store | Club | Roster mgmt | Scoring |
|---|---|---|---|---|---|---|
| super_admin | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| league_organizer | ✓ | ✓ | ✓ | ✓ | – | ✓ |
| club_manager | ✓ | ✓ | ✓ | ✓ (own) | ✓ | – |
| player | ✓ | ✓ | ✓ | ✓ | – | – |
| viewer | ✓ | ✓ | ✓ | ✓ | – | – |

### Release APK
Build `./gradlew assembleRelease` signed with the debug key, then copy the output to `mobile/release/app-release.apk` and commit it. It must install on Android 10+.

---

## 5. Timeline (PKT)
| Time | Frontend | Backend |
|---|---|---|
| until 11:45 | ✅ app shell pushed | skeleton + `/health` + reset/seed + login → **push** |
| 11:45–12:45 | Login, nav, Fixtures, Match detail, Standings (fake data, then real) | sports/events/score, standings, §3 list routes → push |
| 12:45–13:45 | Store + receipt, Club profile, Roster mgmt | checkout, RBAC, roster → push |
| 13:45–14:30 | Scoring entry, offline queue + sync, polish | fixtures ×4, offline-sync, tests + COVERAGE.json → push |
| 14:30 | **Feature freeze.** Release APK into `mobile/release/`, clean `docker compose down -v && up -d --build` test, update README/TRACEABILITY | |
| ≤ 14:55 | **Final push**, then check on GitHub that the commit time is before 15:05 | |
