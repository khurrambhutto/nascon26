# Part I traceability

Statuses describe backend implementation; mobile/device verification belongs to the frontend agent.

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
| NFR-4 | Persistent mobile queue and ordered conflict sync | backend/app/offline.py; /audit/v1/offline-sync; mobile/ | test_platform.py::test_offline_replay; test_offline.py; device check pending | Backend passed; mobile persistence owned by frontend |
| NFR-5 | JWT verification, tenant scope, validation, rate limits | backend/app/auth.py; backend/app/main.py | test_platform.py; test_rbac.py; test_scoring.py | Backend security probes passed; mobile Keystore owned by frontend |
| NFR-7 | Single-command clean Linux startup and seed | docker-compose.yml; backend/Dockerfile; backend/app/seed.py | Clean volumes + docker compose up -d --build; tests/smoke_backend.py | Runtime passed; local build used a verified dependency cache |
