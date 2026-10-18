# Architecture decisions

## ADR-001: FastAPI backend with a self-hosted media server
- Status: Accepted (amended for Part II)
- Context: The team needs a small, readable API matching a strict JSON contract; Part II adds RTMP/WebRTC ingest and HLS/WebRTC playback with no BaaS.
- Decision: Use the pinned Python 3.12 and FastAPI stack. Run MediaMTX 1.9.1 for RTMP (1935), HLS (8888) and WebRTC WHIP/WHEP (8889, ICE on 8189 UDP+TCP). MediaMTX asks the API (`/internal/media/auth`) before every publish/read, records to a shared volume, and the worker archives finished recordings to MinIO as VODs.
- Alternatives: LiveKit needs its own SDKs on every client; server-side FFmpeg mixing costs CPU the 4-vCPU grader cannot spare.
- Consequences: Explicit domain validators translate all contract errors to the required envelope. Stream keys stay authoritative in the API, so revoke/rotate kicks live sessions through the MediaMTX API in milliseconds. The co-stream "composite" is a client-side grid of per-player WebRTC tiles, not a server-mixed video.

## ADR-002: PostgreSQL transactional records
- Status: Accepted
- Context: Concurrent checkout and retries must remain correct across API processes.
- Decision: Persist JSON domain records in PostgreSQL, with a database mutex covering domain transactions.
- Alternatives: In-memory state loses data; per-entity locking is more complex under time constraints.
- Consequences: Strong simple atomicity; independent writes are serialized and throughput is limited. Part II (NFR-2, 550 VUs) runs 4 uvicorn workers; cross-worker live state (WebSocket change counters, connection gauge) lives in Redis, and the two hottest reads (QR inspect, DLQ depth) are cached per worker until any write bumps a Redis version. Measured: k6 p95 170 ms, 0% errors.

## ADR-003: Rules as sport configuration
- Status: Accepted
- Context: New mystery sports must work without code changes.
- Decision: Branch only on event/innings/round scoring models and interpret rule JSON.
- Alternatives: Per-sport conditional logic violates the specification.
- Consequences: Scoring names and roster restrictions are ordinary data.

## ADR-004: Append-only scoring
- Status: Accepted
- Context: Corrections must preserve audit history and update standings.
- Decision: Append signed compensating events and reconstruct scores and standings on read.
- Alternatives: Mutable score counters cannot establish a trustworthy history.
- Consequences: Reads cost more computation but corrections remain transparent.

## ADR-005: Custom JWT authorization and a server-side guest boundary
- Status: Accepted (amended for Part II)
- Context: Five roles need tenant and ownership boundaries with no managed BaaS; Part II adds zero-login guests who must never write.
- Decision: Verify HS256 tokens and apply centralized ownership policies. Guests get `guest_anon_tok_*` tokens with 15-minute validity; self-hosted viewer registration is available; one middleware rejects every POST/PUT/PATCH/DELETE carrying a guest token (or an anonymous chat post) with 403 GUEST_WRITE_FORBIDDEN before any handler runs. Co-stream invites are HMAC-signed with DAY2_SALT.
- Alternatives: External identity services violate self-hosting or add deployment overhead.
- Consequences: Demo credentials seed locally with individual PBKDF2 salts; the token key derives from the private team salt unless a separate JWT_SECRET is supplied.

## ADR-006: Atomic checkout, durable outbox and a Dead-Letter Queue
- Status: Accepted (amended for Part II)
- Context: Inventory changes and order notifications cannot diverge.
- Decision: Commit inventory, order, receipt and outbox in one transaction; worker delivers to Redis and MinIO. Authenticated checkout adds one SMTP receipt job per paid order, claimed with a DB lease and retried outside checkout. The default SMTP sink captures receipts locally in Postgres with a customer-scoped browser inbox.
- Alternatives: Publishing before commit creates events for rolled-back orders.
- Consequences: User notifications require acknowledgement and retry after 1, 2 and 4 seconds; exhausted events enter the durable DLQ. Android keeps its WebSocket in a foreground service while signed in. Delivery is at least once with stable event IDs and email Message-IDs for deduplication. Missing SMTP credentials retain the job without blocking purchase or PDF download; a crash after SMTP acceptance may cause a retry of the same message. Part II: notifications that fail 3 bounded retries are parked in a persistent DLQ (exposed via /audit/v1/moderation/test and nascon_dlq_depth); guest cart merge is one locked transaction keyed by guest_token, so a replay after a network switch returns the merged cart and never double-adds.

## ADR-007: Native mobile with persistent offline replay
- Status: Accepted
- Context: Judges force-close the app offline and then synchronize queued actions.
- Decision: Kotlin/Compose with account-scoped SQLite caches and a queue persisted before transmission; replay through authenticated shared backend services. Outbox delivery scans pending accounts independently of the visible session, using each creator's encrypted JWT and a native persisted JobScheduler fallback. Retain sessions and pending work on token expiry, and try a saved LAN address when USB loopback fails.
- Alternatives: Memory-only queues lose actions on process death.
- Consequences: The phone previews queued scoring events and stores sync outcomes. Cached password proofs and JWTs are encrypted with the Android Keystore; the dedicated outbox credential is removed once its queue drains. Account switching cannot borrow the visible account's privileges. Expired/revoked credentials retain queued work until the original account signs in. First-time login and payments require a server; wireless operation requires a shared reachable LAN. Android controls scheduling when the app is backgrounded or force-stopped.

## ADR-008: Contract, concurrency, grid and device verification
- Status: Accepted (amended for Part II)
- Context: Hidden vectors compare exact fields, errors, signatures and concurrent behavior; Part II also grades Selenium Grid, Appium and k6.
- Decision: Use pytest contract/algorithm tests, a 20-buyer race, measured coverage, and clean Compose smoke checks. Part II adds: Selenium Grid 4 (hub + 2 Chrome + 1 Firefox in Compose) running tests/selenium in parallel (-n 3); Appium 2 deep-link and cross-surface tests on a physical phone; the spec's exact k6 profile; and a GitHub Actions workflow running all of it on push.
- Alternatives: Manual demonstrations alone miss edge cases and rollback failures.
- Consequences: Tests are split by domain and coverage is generated from actual executions. Co-stream composition reuses a self-hosted headless Chrome grid slot, mixing canvas video and Web Audio into one WHIP output, avoiding a new offline media dependency. Each active co-stream consumes one Chrome slot.
