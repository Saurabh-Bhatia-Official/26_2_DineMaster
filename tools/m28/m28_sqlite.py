#!/usr/bin/env python3
"""Safe inventory, backup, and additive UUID backfill for M28 SQLite files.

This tool never connects to PostgreSQL and never guesses cross-database row
matches. Backfill requires --apply and creates a verified SQLite backup first.
Stop the Edge host before running backfill so no application writes race it.
"""

from __future__ import annotations

import argparse
import csv
import json
import sqlite3
import sys
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ENTITY_TABLES = (
    "restaurants",
    "users",
    "customers",
    "tables",
    "categories",
    "products",
    "orders",
    "order_items",
    "kot",
    "order_status_logs",
    "payments",
    "bookings",
    "inventory",
)
MUTABLE_TABLES = {
    "restaurants",
    "tables",
    "customers",
    "categories",
    "products",
    "orders",
    "order_items",
    "kot",
    "bookings",
    "inventory",
}
TIMESTAMP_TABLES = MUTABLE_TABLES | {"users"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )


def open_db(path: Path, *, read_only: bool = False) -> sqlite3.Connection:
    if read_only:
        return sqlite3.connect(f"file:{path.resolve().as_posix()}?mode=ro", uri=True)
    return sqlite3.connect(path)


def list_tables(connection: sqlite3.Connection) -> list[str]:
    return [
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]


def inventory(path: Path) -> dict[str, Any]:
    with closing(open_db(path, read_only=True)) as connection:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        integrity = connection.execute("PRAGMA integrity_check").fetchall()
        tables: list[dict[str, Any]] = []
        for table in list_tables(connection):
            quoted = '"' + table.replace('"', '""') + '"'
            columns = connection.execute(f"PRAGMA table_info({quoted})").fetchall()
            foreign_keys = connection.execute(f"PRAGMA foreign_key_list({quoted})").fetchall()
            count = connection.execute(f"SELECT COUNT(*) FROM {quoted}").fetchone()[0]
            tables.append(
                {
                    "name": table,
                    "rows": count,
                    "columns": [
                        {"name": row[1], "type": row[2], "not_null": bool(row[3]), "pk": row[5]}
                        for row in columns
                    ],
                    "foreign_keys": [
                        {"table": row[2], "from": row[3], "to": row[4]}
                        for row in foreign_keys
                    ],
                }
            )
        return {
            "path": str(path.resolve()),
            "user_version": version,
            "integrity_check": [row[0] for row in integrity],
            "tables": tables,
        }


def backup(path: Path, destination: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    if destination.exists():
        raise FileExistsError(f"Refusing to overwrite backup: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with closing(open_db(path)) as source, closing(sqlite3.connect(destination)) as target:
        source.backup(target)
        target.commit()
    with closing(open_db(destination, read_only=True)) as copied:
        result = copied.execute("PRAGMA integrity_check").fetchone()[0]
        if result != "ok":
            destination.unlink(missing_ok=True)
            raise sqlite3.DatabaseError(f"Backup integrity_check failed: {result}")


def _columns(connection: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')}


def _ensure_metadata(connection: sqlite3.Connection) -> None:
    statements = (
        """
        CREATE TABLE IF NOT EXISTS sync_identity_map (
          entity_type TEXT NOT NULL, sync_id TEXT NOT NULL,
          edge_id INTEGER, cloud_id INTEGER,
          mapping_status TEXT NOT NULL DEFAULT 'UNRECONCILED'
            CHECK (mapping_status IN ('UNRECONCILED','MATCHED','EDGE_ONLY','CLOUD_ONLY','REVIEW_REQUIRED')),
          resolution_note TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          PRIMARY KEY (entity_type, sync_id),
          CHECK (edge_id IS NOT NULL OR cloud_id IS NOT NULL)
        )
        """,
        """CREATE UNIQUE INDEX IF NOT EXISTS ux_sync_identity_map_edge
          ON sync_identity_map(entity_type, edge_id) WHERE edge_id IS NOT NULL;
        """,
        """CREATE UNIQUE INDEX IF NOT EXISTS ux_sync_identity_map_cloud
          ON sync_identity_map(entity_type, cloud_id) WHERE cloud_id IS NOT NULL;
        """,
        """CREATE TABLE IF NOT EXISTS sync_state (
          state_key TEXT PRIMARY KEY, state_value TEXT NOT NULL, updated_at TEXT NOT NULL
        )
        """,
        """CREATE TABLE IF NOT EXISTS sync_cursors (
          stream_key TEXT PRIMARY KEY, cursor_value TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL
        )
        """,
        """CREATE TABLE IF NOT EXISTS sync_device_state (
          device_id TEXT PRIMARY KEY, tenant_sync_id TEXT, branch_sync_id TEXT,
          sync_epoch INTEGER NOT NULL DEFAULT 1 CHECK (sync_epoch > 0),
          schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
          binding_state TEXT NOT NULL DEFAULT 'UNBOUND'
            CHECK (binding_state IN ('UNBOUND','BOUND','REVOKED')),
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          CHECK (binding_state <> 'BOUND' OR (tenant_sync_id IS NOT NULL AND branch_sync_id IS NOT NULL))
        )
        """,
    )
    for statement in statements:
        connection.execute(statement)


def _uuid_sql_expression() -> str:
    return (
        "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || "
        "substr(lower(hex(randomblob(2))),2) || '-' || "
        "substr('89ab',(abs(random()) % 4)+1,1) || "
        "substr(lower(hex(randomblob(2))),2) || '-' || lower(hex(randomblob(6)))"
    )


def _ensure_table_schema(connection: sqlite3.Connection, table: str) -> None:
    columns = _columns(connection, table)
    if "id" not in columns:
        raise ValueError(f"Allowlisted table {table} has no integer id column")
    if "sync_id" not in columns:
        connection.execute(f'ALTER TABLE "{table}" ADD COLUMN sync_id TEXT')
    columns = _columns(connection, table)
    if table in MUTABLE_TABLES and "version" not in columns:
        connection.execute(
            f'ALTER TABLE "{table}" ADD COLUMN version INTEGER NOT NULL DEFAULT 1'
        )
    if table not in {"order_status_logs", "payments"} and "updated_at" not in columns:
        connection.execute(f'ALTER TABLE "{table}" ADD COLUMN updated_at TEXT')
    if table == "payments":
        additions = {
            "event_type": "TEXT NOT NULL DEFAULT 'PAYMENT'",
            "reversal_of_sync_id": "TEXT",
            "currency_code": "TEXT",
            "idempotency_key": "TEXT",
        }
        for name, declaration in additions.items():
            if name not in columns:
                connection.execute(
                    f'ALTER TABLE payments ADD COLUMN "{name}" {declaration}'
                )


def backfill(path: Path) -> Path:
    if not path.is_file():
        raise FileNotFoundError(path)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup_path = path.with_name(f"{path.name}.m28-backup-{stamp}")
    backup(path, backup_path)
    try:
        with closing(open_db(path)) as connection:
            connection.execute("BEGIN IMMEDIATE")
            _ensure_metadata(connection)
            present = set(list_tables(connection))
            now = utc_now()
            for table in ENTITY_TABLES:
                if table not in present:
                    continue
                _ensure_table_schema(connection, table)
                rows = connection.execute(
                    f'SELECT id, sync_id FROM "{table}" ORDER BY id'
                ).fetchall()
                for local_id, sync_id in rows:
                    stable_id = sync_id or str(uuid.uuid4())
                    if table in TIMESTAMP_TABLES:
                        connection.execute(
                            f'UPDATE "{table}" SET sync_id=COALESCE(sync_id,?), '
                            "updated_at=COALESCE(updated_at,?) "
                            "WHERE id=? AND (sync_id IS NULL OR updated_at IS NULL)",
                            (stable_id, now, local_id),
                        )
                    else:
                        connection.execute(
                            f'UPDATE "{table}" SET sync_id=COALESCE(sync_id,?) '
                            "WHERE id=? AND sync_id IS NULL",
                            (stable_id, local_id),
                        )
                    if table == "payments":
                        connection.execute(
                            "UPDATE payments SET event_type=COALESCE(event_type,'PAYMENT'), "
                            "idempotency_key=COALESCE(idempotency_key,sync_id) WHERE id=?",
                            (local_id,),
                        )
                    connection.execute(
                        "INSERT OR IGNORE INTO sync_identity_map "
                        "(entity_type,sync_id,edge_id,mapping_status,created_at,updated_at) "
                        "VALUES (?,?,?,'UNRECONCILED',?,?)",
                        (table, stable_id, local_id, now, now),
                    )

                connection.execute(
                    f'CREATE UNIQUE INDEX IF NOT EXISTS "ux_m28_{table}_sync_id" '
                    f'ON "{table}"(sync_id)'
                )
                connection.execute(
                    f'''CREATE TRIGGER IF NOT EXISTS "trg_m28_{table}_sync_id_immutable"
                        BEFORE UPDATE OF sync_id ON "{table}"
                        WHEN OLD.sync_id IS NOT NULL AND NEW.sync_id IS NOT OLD.sync_id
                          AND COALESCE((SELECT state_value FROM sync_state WHERE state_key='reconciliation_mode'),'false') <> 'true'
                        BEGIN SELECT RAISE(ABORT, 'sync_id is immutable'); END'''
                )
                _create_insert_trigger(connection, table)
                if table in MUTABLE_TABLES:
                    _create_version_triggers(connection, table)
                elif table in TIMESTAMP_TABLES:
                    _create_timestamp_trigger(connection, table)
            if "payments" in present:
                connection.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS ux_m28_payments_idempotency "
                    "ON payments(idempotency_key) WHERE idempotency_key IS NOT NULL"
                )
            connection.execute(
                "INSERT OR IGNORE INTO sync_state(state_key,state_value,updated_at) "
                "VALUES ('schema_version','1',?)",
                (now,),
            )
            connection.execute(
                "INSERT OR IGNORE INTO sync_state(state_key,state_value,updated_at) "
                "VALUES ('reconciliation_mode','false',?)",
                (now,),
            )
            if connection.execute("SELECT 1 FROM sync_device_state LIMIT 1").fetchone() is None:
                connection.execute(
                    "INSERT INTO sync_device_state(device_id,sync_epoch,schema_version,"
                    "binding_state,created_at,updated_at) VALUES (?,1,1,'UNBOUND',?,?)",
                    (str(uuid.uuid4()), now, now),
                )
            connection.commit()
        return backup_path
    except Exception:
        # The backup is retained for diagnosis/restoration. The enclosing
        # contextlib.closing closes the connection while the transaction is
        # still active; sqlite3 then discards that transaction on close.
        raise


def _create_insert_trigger(connection: sqlite3.Connection, table: str) -> None:
    generated = _uuid_sql_expression()
    timestamp_set = (
        ", updated_at=COALESCE(NEW.updated_at,strftime('%Y-%m-%dT%H:%M:%fZ','now'))"
        if table in TIMESTAMP_TABLES
        else ""
    )
    payment_set = (
        "UPDATE payments SET idempotency_key=COALESCE(NEW.idempotency_key,sync_id) "
        "WHERE id=NEW.id;"
        if table == "payments"
        else ""
    )
    connection.execute(
        f'''
        CREATE TRIGGER IF NOT EXISTS "trg_m28_{table}_identity_after_insert"
        AFTER INSERT ON "{table}"
        BEGIN
          UPDATE "{table}" SET sync_id=COALESCE(NEW.sync_id,{generated}){timestamp_set}
            WHERE id=NEW.id;
          {payment_set}
          INSERT OR IGNORE INTO sync_identity_map
            (entity_type,sync_id,edge_id,mapping_status,created_at,updated_at)
          SELECT '{table}',sync_id,id,'UNRECONCILED',
            strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now')
          FROM "{table}" WHERE id=NEW.id AND sync_id IS NOT NULL;
        END
        '''
    )


def _create_version_triggers(connection: sqlite3.Connection, table: str) -> None:
    connection.execute(
        f'''
        CREATE TRIGGER IF NOT EXISTS "trg_m28_{table}_insert_timestamp"
        AFTER INSERT ON "{table}" WHEN NEW.updated_at IS NULL
        BEGIN
          UPDATE "{table}" SET updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
          WHERE id=NEW.id;
        END
        '''
    )
    connection.execute(
        f'''
        CREATE TRIGGER IF NOT EXISTS "trg_m28_{table}_version_timestamp"
        AFTER UPDATE ON "{table}"
        WHEN OLD.updated_at IS NOT NULL AND NEW.sync_id IS OLD.sync_id
          AND NEW.updated_at IS OLD.updated_at
        BEGIN
          UPDATE "{table}" SET version=MAX(OLD.version+1,NEW.version),
            updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=NEW.id;
        END
        '''
    )


def _create_timestamp_trigger(connection: sqlite3.Connection, table: str) -> None:
    connection.execute(
        f'''CREATE TRIGGER IF NOT EXISTS "trg_m28_{table}_update_timestamp"
            AFTER UPDATE ON "{table}" WHEN NEW.updated_at IS OLD.updated_at
            BEGIN
              UPDATE "{table}" SET updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
              WHERE id=NEW.id;
            END'''
    )


def export_reconciliation(path: Path, output: Path) -> None:
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite: {output}")
    with closing(open_db(path, read_only=True)) as connection, output.open(
        "w", newline="", encoding="utf-8"
    ) as stream:
        writer = csv.writer(stream)
        writer.writerow(
            [
                "entity_type",
                "edge_id",
                "edge_sync_id",
                "cloud_id",
                "cloud_sync_id",
                "canonical_sync_id",
                "decision",
                "notes",
            ]
        )
        present = set(list_tables(connection))
        for table in ENTITY_TABLES:
            if table not in present or "sync_id" not in _columns(connection, table):
                continue
            for local_id, sync_id in connection.execute(
                f'SELECT id,sync_id FROM "{table}" ORDER BY id'
            ):
                writer.writerow([table, local_id, sync_id, "", "", "", "", ""])


def generate_postgres_reconciliation_sql(mapping_path: Path, output: Path) -> int:
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite: {output}")
    statements: list[str] = []
    seen_canonical: set[tuple[str, str]] = set()
    with mapping_path.open(newline="", encoding="utf-8-sig") as stream:
        for line_number, row in enumerate(csv.DictReader(stream), start=2):
            if (row.get("decision") or "").strip().upper() != "MATCHED":
                continue
            entity = (row.get("entity_type") or "").strip()
            table = "payment_events" if entity == "payments" else entity
            if entity not in ENTITY_TABLES:
                raise ValueError(f"Line {line_number}: entity is not in the M28 allowlist")
            edge_id = _positive_id(row.get("edge_id"), line_number, "edge_id")
            cloud_id = _positive_id(row.get("cloud_id"), line_number, "cloud_id")
            edge_uuid = _canonical_uuid(row.get("edge_sync_id"), line_number, "edge_sync_id")
            cloud_uuid = _canonical_uuid(row.get("cloud_sync_id"), line_number, "cloud_sync_id")
            canonical = _canonical_uuid(row.get("canonical_sync_id"), line_number, "canonical_sync_id")
            key = (entity, canonical)
            if key in seen_canonical:
                raise ValueError(f"Line {line_number}: duplicate canonical UUID in entity {entity}")
            seen_canonical.add(key)
            entity_sql = entity.replace("'", "''")
            statements.append(
                f"""DO $m28$
DECLARE changed INTEGER;
BEGIN
  IF EXISTS (SELECT 1 FROM public.{table} WHERE sync_id = '{canonical}' AND id <> {cloud_id}) THEN
    RAISE EXCEPTION 'canonical sync UUID already belongs to another {entity} row';
  END IF;
  UPDATE public.{table} SET sync_id = '{canonical}'
    WHERE id = {cloud_id} AND sync_id = '{cloud_uuid}';
  GET DIAGNOSTICS changed = ROW_COUNT;
  IF changed <> 1 THEN RAISE EXCEPTION 'cloud row changed since reconciliation export'; END IF;
  UPDATE ops.sync_identity_map
    SET sync_id = '{canonical}', edge_id = {edge_id}, mapping_status = 'MATCHED',
        resolution_note = 'approved mapping CSV', updated_at = CURRENT_TIMESTAMP
    WHERE entity_type = '{entity_sql}' AND sync_id = '{cloud_uuid}' AND cloud_id = {cloud_id};
  GET DIAGNOSTICS changed = ROW_COUNT;
  IF changed <> 1 THEN RAISE EXCEPTION 'cloud identity-map row changed since export'; END IF;
END
$m28$;"""
            )
    if not statements:
        raise ValueError("The CSV contains no MATCHED rows that need UUID reconciliation")
    sql = (
        "-- Review against the approved reconciliation CSV before execution.\n"
        "-- Run only on a backed-up, non-production database during the migration window.\n"
        "BEGIN;\n"
        "DO $m28$ DECLARE changed INTEGER; BEGIN "
        "UPDATE ops.sync_control SET reconciliation_mode = TRUE, updated_at = CURRENT_TIMESTAMP "
        "WHERE singleton AND NOT sync_enabled; GET DIAGNOSTICS changed = ROW_COUNT; "
        "IF changed <> 1 THEN RAISE EXCEPTION 'sync is enabled or control row is missing'; END IF; END $m28$;\n"
        + "\n".join(statements)
        + "\nUPDATE ops.sync_control SET reconciliation_mode = FALSE, updated_at = CURRENT_TIMESTAMP WHERE singleton;\n"
        "COMMIT;\n"
    )
    output.write_text(sql, encoding="utf-8", newline="\n")
    return len(statements)


def apply_local_reconciliation(path: Path, mapping_path: Path) -> Path:
    rows: list[dict[str, str]] = []
    with mapping_path.open(newline="", encoding="utf-8-sig") as stream:
        for line_number, row in enumerate(csv.DictReader(stream), start=2):
            if (row.get("decision") or "").strip().upper() != "MATCHED":
                continue
            entity = (row.get("entity_type") or "").strip()
            if entity not in ENTITY_TABLES:
                raise ValueError(f"Line {line_number}: entity is not in the M28 allowlist")
            rows.append(
                {
                    "entity": entity,
                    "edge_id": str(_positive_id(row.get("edge_id"), line_number, "edge_id")),
                    "edge_sync_id": _canonical_uuid(row.get("edge_sync_id"), line_number, "edge_sync_id"),
                    "cloud_id": str(_positive_id(row.get("cloud_id"), line_number, "cloud_id")),
                    "canonical_sync_id": _canonical_uuid(row.get("canonical_sync_id"), line_number, "canonical_sync_id"),
                }
            )
    if not rows:
        raise ValueError("The CSV contains no MATCHED mappings")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup_path = path.with_name(f"{path.name}.m28-reconcile-backup-{stamp}")
    backup(path, backup_path)
    with closing(open_db(path)) as connection:
        connection.execute("BEGIN IMMEDIATE")
        mode = connection.execute(
            "SELECT state_value FROM sync_state WHERE state_key='reconciliation_mode'"
        ).fetchone()
        enabled = connection.execute(
            "SELECT state_value FROM sync_state WHERE state_key='sync_enabled'"
        ).fetchone()
        if mode is None or enabled is not None and enabled[0] == "true":
            raise ValueError("Local reconciliation is unavailable or sync is enabled")
        connection.execute(
            "UPDATE sync_state SET state_value='true',updated_at=? WHERE state_key='reconciliation_mode'",
            (utc_now(),),
        )
        for row in rows:
            entity = row["entity"]
            collision = connection.execute(
                f'SELECT id FROM "{entity}" WHERE sync_id=? AND id<>?',
                (row["canonical_sync_id"], int(row["edge_id"])),
            ).fetchone()
            if collision is not None:
                raise ValueError(f"Canonical UUID already belongs to another local {entity} row")
            changed = connection.execute(
                f'UPDATE "{entity}" SET sync_id=? WHERE id=? AND sync_id=?',
                (row["canonical_sync_id"], int(row["edge_id"]), row["edge_sync_id"]),
            ).rowcount
            if changed != 1:
                raise ValueError(f"Local {entity} row changed since reconciliation export")
            changed = connection.execute(
                "UPDATE sync_identity_map SET sync_id=?,cloud_id=?,mapping_status='MATCHED', "
                "resolution_note='approved mapping CSV',updated_at=? "
                "WHERE entity_type=? AND sync_id=? AND edge_id=?",
                (row["canonical_sync_id"], int(row["cloud_id"]), utc_now(), entity,
                 row["edge_sync_id"], int(row["edge_id"])),
            ).rowcount
            if changed != 1:
                raise ValueError(f"Local identity-map row changed since reconciliation export")
        connection.execute(
            "UPDATE sync_state SET state_value='false',updated_at=? WHERE state_key='reconciliation_mode'",
            (utc_now(),),
        )
        connection.commit()
    return backup_path


def _positive_id(value: str | None, line_number: int, column: str) -> int:
    try:
        parsed = int(value or "")
    except ValueError as exc:
        raise ValueError(f"Line {line_number}: {column} must be a positive integer") from exc
    if parsed <= 0:
        raise ValueError(f"Line {line_number}: {column} must be a positive integer")
    return parsed


def _canonical_uuid(value: str | None, line_number: int, column: str) -> str:
    try:
        parsed = uuid.UUID(value or "")
    except (ValueError, AttributeError) as exc:
        raise ValueError(f"Line {line_number}: {column} must be a UUID") from exc
    if str(parsed) != (value or "").strip().lower():
        raise ValueError(f"Line {line_number}: {column} must be canonical UUID text")
    return str(parsed)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inv = sub.add_parser("inventory", help="Read-only SQLite schema and integrity report")
    inv.add_argument("database", type=Path)
    bkp = sub.add_parser("backup", help="Create and verify a consistent SQLite backup")
    bkp.add_argument("database", type=Path)
    bkp.add_argument("output", type=Path)
    migrate = sub.add_parser("backfill", help="Add UUID sidecars and M28 metadata")
    migrate.add_argument("database", type=Path)
    migrate.add_argument("--apply", action="store_true", help="Required to change the database")
    reconcile = sub.add_parser("reconciliation-template", help="Export rows without guessing matches")
    reconcile.add_argument("database", type=Path)
    reconcile.add_argument("output", type=Path)
    pg_sql = sub.add_parser("reconciliation-sql", help="Generate guarded PostgreSQL SQL from reviewed matches")
    pg_sql.add_argument("mapping_csv", type=Path)
    pg_sql.add_argument("output", type=Path)
    local_reconcile = sub.add_parser("apply-reconciliation", help="Apply reviewed UUID matches to SQLite")
    local_reconcile.add_argument("database", type=Path)
    local_reconcile.add_argument("mapping_csv", type=Path)
    local_reconcile.add_argument("--apply", action="store_true", help="Required to change SQLite")
    local_reconcile.add_argument("--cloud-transaction-committed", action="store_true",
                                 help="Assert reviewed PostgreSQL reconciliation transaction already committed")
    args = parser.parse_args(argv)

    try:
        if args.command == "inventory":
            print(json.dumps(inventory(args.database), indent=2))
        elif args.command == "backup":
            backup(args.database, args.output)
            print(f"Verified backup created: {args.output.resolve()}")
        elif args.command == "backfill":
            if not args.apply:
                parser.error("backfill is read-only unless --apply is supplied")
            backup_path = backfill(args.database)
            print(f"UUID backfill committed. Verified backup: {backup_path.resolve()}")
        elif args.command == "reconciliation-template":
            export_reconciliation(args.database, args.output)
            print(f"Reconciliation template created: {args.output.resolve()}")
        elif args.command == "reconciliation-sql":
            count = generate_postgres_reconciliation_sql(args.mapping_csv, args.output)
            print(f"Generated guarded PostgreSQL SQL for {count} reviewed UUID mappings: {args.output.resolve()}")
        elif args.command == "apply-reconciliation":
            if not args.apply or not args.cloud_transaction_committed:
                parser.error("apply-reconciliation requires --apply and --cloud-transaction-committed")
            backup_path = apply_local_reconciliation(args.database, args.mapping_csv)
            print(f"Applied reviewed local UUID mappings. Verified backup: {backup_path.resolve()}")
        return 0
    except (OSError, sqlite3.Error, ValueError) as exc:
        print(f"M28 SQLite tool error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
