# Part I traceability

Statuses describe backend implementation; mobile/device verification belongs to the frontend agent.

| Req ID | Requirement | Implementation | Tests | Status |
|---|---|---|---|---|
| FR-1.1 | JSON sport taxonomy and append-only signed scoring | backend/app/scoring.py; /audit/v1/sports, /events, /score | backend/tests/test_platform.py; test_scoring.py | Implemented; validation in progress |
| FR-1.2 | Configurable season leagues and tournaments | backend/app/scoring.py; /audit/v1/leagues | test_scoring.py | Core league registration implemented |
| FR-1.3 | Four fixture algorithms and scheduling | backend/app/fixtures.py; /audit/v1/fixtures | test_fixtures.py | Algorithms implemented; validation in progress |
| FR-1.4 | Standings, H2H mini-leagues and qualification | backend/app/standings.py | test_standings.py | Implemented; validation in progress |
| FR-2.1 | Tenant-isolated clubs and profiles | backend/app/auth.py; /api/v1/clubs/{id} | test_platform.py; test_rbac.py | Implemented; validation in progress |
| FR-2.2 | Transfer, release, loan and immutable history | backend/app/roster.py | test_roster.py | Implemented; validation in progress |
| FR-2.3 | Atomic checkout, mock gateway, signed PDF, outbox | backend/app/store.py; backend/app/worker.py | test_platform.py; test_store.py | Core implemented; validation in progress |
| FR-5.1 | Five-role authorization matrix | backend/app/auth.py; /audit/v1/rbac/* | test_rbac.py | Implemented; validation in progress |
| NFR-3 | Exactly one purchase from 20 buyers of last unit | backend/app/storage.py; backend/app/store.py | test_platform.py::test_checkout_twenty_concurrent_buyers | Test written |
| NFR-4 | Persistent mobile queue and ordered conflict sync | backend/app/offline.py; /audit/v1/offline-sync; mobile/ | test_platform.py::test_offline_replay; test_offline.py; device check pending | Backend implemented; mobile owned by frontend |
| NFR-5 | JWT verification, tenant scope, validation, rate limits | backend/app/auth.py; backend/app/main.py | test_platform.py; test_rbac.py | Implemented; validation in progress |
| NFR-7 | Single-command clean Linux startup and seed | docker-compose.yml; backend/Dockerfile; backend/app/seed.py | Docker clean-build smoke check | Validation in progress |
