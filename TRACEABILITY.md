# Part I traceability

Backend statuses come from pytest; mobile statuses were verified on a physical Android phone (Samsung SM-A175F) against the real backend.

| Req ID | Requirement | Implementation | Tests | Status |
|---|---|---|---|---|
| FR-1.1 | JSON sport taxonomy and append-only signed scoring | backend/app/scoring.py; /audit/v1/sports, /events, /score | backend/tests/test_platform.py; test_scoring.py | Backend tests passed |
| FR-1.2 | Configurable season leagues and tournaments | backend/app/scoring.py; /audit/v1/leagues; PATCH /api/v1/leagues/{id}/configuration | test_scoring.py; test_scheduling.py | Registration and configuration tested; fixture registration is caller-driven |
| FR-1.3 | Four fixture algorithms and scheduling | backend/app/fixtures.py; backend/app/scheduling.py | test_fixtures.py; test_scheduling.py | Backend tests passed |
| FR-1.4 | Standings, H2H mini-leagues and qualification | backend/app/standings.py | test_standings.py | Backend tests passed |
| FR-2.1 | Tenant-isolated clubs and profiles | backend/app/auth.py; /api/v1/clubs/{id}; tenant branding API | test_platform.py; test_rbac.py; test_scheduling.py | Backend tests passed |
| FR-2.2 | Transfer, release, loan and immutable history | backend/app/roster.py | test_roster.py | Backend tests passed |
| FR-2.3 | Atomic checkout, mock gateway, signed PDF, outbox, fulfilment/refunds | backend/app/store.py; backend/app/orders.py; backend/app/worker.py | test_platform.py; test_store.py; test_orders.py; live Redis/MinIO check | Backend tests passed; receipt archived and event published |
| FR-5.1 | Five-role authorization matrix | backend/app/auth.py; /audit/v1/rbac/* | test_rbac.py | Full negative matrix passed |
| NFR-3 | Exactly one purchase from 20 buyers of last unit | backend/app/storage.py; backend/app/store.py | test_platform.py::test_checkout_twenty_concurrent_buyers; tests/smoke_backend.py | Passed with PostgreSQL, including two API processes |
| NFR-4 | Persistent mobile queue and ordered conflict sync | backend/app/offline.py; /audit/v1/offline-sync; mobile/.../data/LocalDb.kt (SQLite cache + pending_actions), Repository.kt (sendOrQueue, syncNow), AppViewModel.kt (auto-sync) | test_platform.py::test_offline_replay; test_offline.py; device: offline score + roster action queued, survived force-close, auto-synced on reconnect | Passed (backend + device) |
| NFR-5 | JWT verification, tenant scope, validation, rate limits, secure token storage | backend/app/auth.py; backend/app/main.py; mobile/.../data/SecureStore.kt (JWT encrypted with Android Keystore AES-GCM key) | test_platform.py; test_rbac.py; test_scoring.py; device: 401 signs the user out | Passed (backend + device) |
| NFR-7 | Single-command clean Linux startup and seed | docker-compose.yml; backend/Dockerfile; backend/app/seed.py | Clean volumes + docker compose up -d --build; tests/smoke_backend.py | Runtime passed; local build used a verified dependency cache |

## Mobile app (spec Section 8): `mobile/`, release APK at `mobile/release/app-release.apk`

| Req ID | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| M-8.1 | APK for Android 10+, editable server address, Keystore token | app/build.gradle.kts (minSdk 26, signed release); ui/screens/AccountScreens.kt SettingsScreen; data/SecureStore.kt | apksigner verify; installed on device | Done |
| M-8.2a | Login, role-aware home | ui/screens/LoginScreen.kt; ui/MainShell.kt (manager lands on Club) | Device: empty/wrong-password errors, all demo accounts | Done |
| M-8.2b | Fixtures + match detail (live score, timeline, pinned product) | ui/screens/FixturesScreen.kt; MatchScreen.kt (SSE stream, 3 s poll fallback) | Device against real backend | Done |
| M-8.2c | Standings with qualification status | ui/screens/StandingsScreen.kt | Device | Done |
| M-8.2d | Club profile (info, roster, staff, venue) | ui/screens/ClubScreen.kt | Device | Done |
| M-8.2e | Store: catalogue, variants, cart, mock gateway, receipt PDF | ui/screens/StoreScreens.kt | Device: declined card error, paid order with WELCOME10, PDF opened | Done |
| M-8.2f | Roster management: add / transfer / loan / release | ui/screens/RosterScreen.kt; POST /api/v1/clubs/{id}/players | Device + test_roster.py::test_manager_adds_player_to_own_club_only | Done |
| M-8.2g | Scoring entry: score + compensating event (buttons from sport config) | ui/screens/MatchScreen.kt ScoringPanel/TimelineRow | Device: goal 1-0 to 2-0, undo with reason | Done |
| M-8.2h | Always-visible Online/Offline/Syncing + pending queue | ui/components/Components.kt ConnectionStrip; AccountScreens.kt SyncScreen | Device | Done |
| M-8.4 | Offline desk check (cache, airplane mode, queue, force-close, auto-sync, plain-language results) | data/LocalDb.kt; data/Repository.kt; ui/AppViewModel.kt | Device: full flow passed for scoring and roster | Done |
| M-8.6 | UI/HCI: tenant colour + logo, 48dp targets, text+icon status, confirmations, empty states, rotation | ui/theme/Theme.kt; ui/components/Components.kt | Device incl. landscape | Done |
