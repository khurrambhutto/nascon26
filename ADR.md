# Architecture decisions

## ADR-001: FastAPI backend
- Status: Accepted
- Context: The team needs a small, readable API matching a strict JSON contract.
- Decision: Use the pinned Python 3.12 and FastAPI stack.
- Alternatives: A larger framework adds sprint setup cost.
- Consequences: Explicit domain validators translate all contract errors to the required envelope.

## ADR-002: PostgreSQL transactional records
- Status: Accepted
- Context: Concurrent checkout and retries must remain correct across API processes.
- Decision: Persist JSON domain records in PostgreSQL, with a database mutex covering domain transactions.
- Alternatives: In-memory state loses data; per-entity locking is more complex under time constraints.
- Consequences: Strong simple atomicity; independent writes are serialized and throughput is limited.

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

## ADR-005: Custom JWT authorization
- Status: Accepted
- Context: Five roles need tenant and ownership boundaries with no managed BaaS.
- Decision: Verify HS256 tokens and apply centralized ownership policies.
- Alternatives: External identity services violate self-hosting or add deployment overhead.
- Consequences: Demo credentials seed locally; deployment must replace default secrets.

## ADR-006: Atomic checkout and durable outbox
- Status: Accepted
- Context: Inventory changes and order notifications cannot diverge.
- Decision: Commit inventory, order, receipt and outbox in one transaction; worker delivers to Redis and MinIO.
- Alternatives: Publishing before commit creates events for rolled-back orders.
- Consequences: Delivery is at least once with stable event IDs for deduplication.

## ADR-007: Native mobile with persistent offline replay
- Status: Accepted
- Context: Judges force-close the app offline and then synchronize queued actions.
- Decision: Kotlin/Compose with SQLite cache and persistent action queue, replayed through the shared backend services.
- Alternatives: Memory-only queues lose actions on process death.
- Consequences: Frontend implements local persistence; backend returns per-action conflict resolutions.

## ADR-008: Contract and concurrency verification
- Status: Accepted
- Context: Hidden vectors compare exact fields, errors, signatures and concurrent behavior.
- Decision: Use pytest contract/algorithm tests, a 20-buyer race, measured coverage, and clean Compose smoke checks.
- Alternatives: Manual demonstrations alone miss edge cases and rollback failures.
- Consequences: Tests are split by domain and coverage is generated from actual executions.
