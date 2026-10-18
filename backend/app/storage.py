"""Transactional JSON records. PostgreSQL row lock serializes domain mutations.

Every request uses one transaction, including checkout, idempotency and its outbox.
The lock is in PostgreSQL, so correctness is preserved across API workers.
SQLite is supported for isolated, fast unit tests only.
"""
import os
from contextlib import contextmanager
from threading import RLock

from sqlalchemy import JSON, Column, Integer, MetaData, String, Table, create_engine, delete, insert, select, text, update
from sqlalchemy.pool import StaticPool

metadata = MetaData()
records = Table("domain_records", metadata, Column("kind", String(40), primary_key=True), Column("id", String(128), primary_key=True), Column("data", JSON, nullable=False))
mutex = Table("domain_mutex", metadata, Column("id", Integer, primary_key=True))
url = os.environ.get("DATABASE_URL", "sqlite:///./nascon.db")
options = {"connect_args": {"check_same_thread": False}} if url.startswith("sqlite") else {"pool_pre_ping": True, "pool_size": 10, "max_overflow": 30}
if url in ("sqlite://", "sqlite:///:memory:"):
    options["poolclass"] = StaticPool
engine = create_engine(url, **options)
local_lock = RLock()


def initialize():
    with engine.begin() as connection:
        if engine.dialect.name == "postgresql":
            # Several uvicorn workers start at once; only one may create the schema at a time.
            connection.execute(text("SELECT pg_advisory_xact_lock(2026)"))
        metadata.create_all(connection)
        if connection.execute(select(mutex.c.id)).first() is None:
            connection.execute(insert(mutex).values(id=1))


class Store:
    def __init__(self, connection):
        self.connection = connection

    def get(self, kind, key, default=None):
        row = self.connection.execute(select(records.c.data).where(records.c.kind == kind, records.c.id == str(key))).first()
        return row[0] if row else default

    def put(self, kind, key, data):
        condition = (records.c.kind == kind) & (records.c.id == str(key))
        result = self.connection.execute(update(records).where(condition).values(data=data))
        if not result.rowcount:
            self.connection.execute(insert(records).values(kind=kind, id=str(key), data=data))
        return data

    def all(self, kind):
        return [row[0] for row in self.connection.execute(select(records.c.data).where(records.c.kind == kind).order_by(records.c.id))]

    def remove(self, kind, key):
        self.connection.execute(delete(records).where(records.c.kind == kind, records.c.id == str(key)))

    def clear(self):
        self.connection.execute(delete(records))

    def require(self, kind, key, code=None):
        from .common import fail
        value = self.get(kind, key)
        if value is None:
            fail(404, code or kind.upper() + "_NOT_FOUND")
        return value


@contextmanager
def transaction():
    with local_lock, engine.begin() as connection:
        connection.execute(select(mutex.c.id).where(mutex.c.id == 1).with_for_update()).first()
        yield Store(connection)


@contextmanager
def reader():
    """Read-only access without the global write lock (used by high-traffic GET probes)."""
    with engine.connect() as connection:
        yield Store(connection)
