import contextlib
import csv
import io
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from m28_sqlite import (
    apply_local_reconciliation,
    backup,
    backfill,
    export_reconciliation,
    generate_postgres_reconciliation_sql,
    inventory,
    main,
)


def create_fixture(path: Path) -> None:
    with closing_sqlite(path) as db:
        db.executescript(
            """
            PRAGMA user_version=7;
            CREATE TABLE restaurants(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);
            CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL);
            CREATE TABLE customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);
            CREATE TABLE tables(id INTEGER PRIMARY KEY AUTOINCREMENT, table_number TEXT NOT NULL);
            CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);
            CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);
            CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, total_amount REAL NOT NULL);
            CREATE TABLE order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL);
            CREATE TABLE kot(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL);
            CREATE TABLE order_status_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL);
            CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL,
              amount REAL NOT NULL, payment_mode TEXT NOT NULL, payment_time TEXT NOT NULL);
            CREATE TABLE bookings(id INTEGER PRIMARY KEY AUTOINCREMENT, booking_time TEXT NOT NULL);
            CREATE TABLE inventory(id INTEGER PRIMARY KEY AUTOINCREMENT, item_name TEXT NOT NULL);
            INSERT INTO restaurants(name) VALUES ('Branch A');
            INSERT INTO orders(total_amount) VALUES (250.00);
            INSERT INTO payments(order_id,amount,payment_mode,payment_time)
              VALUES (1,250.00,'Cash','2026-01-01T10:00:00Z');
            """
        )
        db.commit()


class M28SqliteToolTests(unittest.TestCase):
    def test_inventory_is_read_only_and_backfill_requires_apply(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            create_fixture(path)
            with closing_sqlite(path) as db:
                before = db.execute("SELECT COUNT(*) FROM orders").fetchone()[0]
                tables_before = {
                    row[0]
                    for row in db.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    )
                }

            self.assertEqual(inventory(path)["integrity_check"], ["ok"])
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as raised:
                    main(["backfill", str(path)])
            self.assertEqual(raised.exception.code, 2)
            with closing_sqlite(path) as db:
                self.assertEqual(db.execute("SELECT COUNT(*) FROM orders").fetchone()[0], before)
                tables_after = {
                    row[0]
                    for row in db.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    )
                }
            self.assertEqual(tables_after, tables_before)

    def test_backfill_is_additive_stable_and_backed_up(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            create_fixture(path)

            backup_path = backfill(path)
            self.assertTrue(backup_path.exists())
            before = inventory(path)
            with closing_sqlite(path) as db:
                branch = db.execute("SELECT id,sync_id FROM restaurants").fetchone()
                order = db.execute("SELECT id,sync_id,version,updated_at FROM orders").fetchone()
                payment = db.execute(
                    "SELECT id,sync_id,event_type,idempotency_key FROM payments"
                ).fetchone()
                self.assertEqual(branch[0], 1)
                self.assertEqual(order[0], 1)
                self.assertEqual(order[2], 1)
                self.assertIsNotNone(order[3])
                self.assertEqual(payment[0], 1)
                self.assertEqual(payment[2], "PAYMENT")
                self.assertEqual(payment[1], payment[3])
                stable_order_uuid = order[1]

                db.execute("INSERT INTO orders(total_amount) VALUES (10.00)")
                new_id, new_uuid = db.execute(
                    "SELECT id,sync_id FROM orders WHERE id=2"
                ).fetchone()
                self.assertEqual(new_id, 2)
                self.assertRegex(new_uuid, r"^[0-9a-f-]{36}$")
                self.assertEqual(
                    db.execute("SELECT COUNT(*) FROM sync_identity_map WHERE edge_id=2 AND entity_type='orders'").fetchone()[0],
                    1,
                )
                db.execute("UPDATE orders SET total_amount=11.00 WHERE id=2")
                self.assertEqual(
                    db.execute("SELECT version FROM orders WHERE id=2").fetchone()[0], 2
                )

                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute("UPDATE orders SET sync_id=? WHERE id=2", ("00000000-0000-4000-8000-000000000001",))
                db.commit()

            second_backup = backfill(path)
            self.assertTrue(second_backup.exists())
            with closing_sqlite(path) as db:
                self.assertEqual(
                    db.execute("SELECT sync_id FROM orders WHERE id=1").fetchone()[0],
                    stable_order_uuid,
                )
            self.assertEqual(before["integrity_check"], ["ok"])

    def test_reconciliation_requires_explicit_review_and_outputs_guarded_sql(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            template = Path(directory) / "mapping.csv"
            sql_path = Path(directory) / "cloud.sql"
            create_fixture(path)
            backfill(path)
            export_reconciliation(path, template)
            with template.open(newline="", encoding="utf-8") as stream:
                rows = list(csv.DictReader(stream))
            order = next(row for row in rows if row["entity_type"] == "orders")
            order.update(
                {
                    "cloud_id": "77",
                    "cloud_sync_id": "00000000-0000-4000-8000-000000000077",
                    "canonical_sync_id": "00000000-0000-4000-8000-000000000099",
                    "decision": "MATCHED",
                    "notes": "approved by operator",
                }
            )
            with template.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)

            count = generate_postgres_reconciliation_sql(template, sql_path)
            self.assertEqual(count, 1)
            cloud_sql = sql_path.read_text(encoding="utf-8")
            self.assertIn("NOT sync_enabled", cloud_sql)
            self.assertIn("cloud row changed since reconciliation export", cloud_sql)
            self.assertIn("SET sync_id = '00000000-0000-4000-8000-000000000099'", cloud_sql)

            local_backup = apply_local_reconciliation(path, template)
            self.assertTrue(local_backup.exists())
            with closing_sqlite(path) as db:
                self.assertEqual(
                    db.execute("SELECT sync_id FROM orders WHERE id=1").fetchone()[0],
                    "00000000-0000-4000-8000-000000000099",
                )
                mapping = db.execute(
                    "SELECT cloud_id,mapping_status FROM sync_identity_map "
                    "WHERE entity_type='orders' AND edge_id=1"
                ).fetchone()
                self.assertEqual(mapping, (77, "MATCHED"))
                self.assertEqual(
                    db.execute("SELECT state_value FROM sync_state WHERE state_key='reconciliation_mode'").fetchone()[0],
                    "false",
                )
                db.commit()

    def test_interrupted_backfill_rolls_back_schema_and_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            create_fixture(path)
            with closing_sqlite(path) as db:
                db.execute("INSERT INTO orders(total_amount) VALUES (300.00)")
                db.execute("ALTER TABLE orders ADD COLUMN sync_id TEXT")
                db.execute("UPDATE orders SET sync_id='00000000-0000-4000-8000-000000000001'")
                db.commit()

            with self.assertRaises(sqlite3.IntegrityError):
                backfill(path)

            with closing_sqlite(path) as db:
                self.assertNotIn("sync_identity_map", list_tables_for_test(db))
                self.assertNotIn(
                    "version", {row[1] for row in db.execute('PRAGMA table_info("orders")')}
                )
                self.assertEqual(db.execute("SELECT COUNT(*) FROM orders").fetchone()[0], 2)
                self.assertEqual(
                    db.execute("SELECT COUNT(DISTINCT sync_id) FROM orders").fetchone()[0], 1
                )
            backups = list(Path(directory).glob("edge.sqlite.m28-backup-*"))
            self.assertEqual(len(backups), 1)
            assert_file_can_be_renamed(self, path)
            assert_file_can_be_renamed(self, backups[0])

    def test_sqlite_handles_are_released_after_inventory_backup_and_backfill(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            backup_path = Path(directory) / "manual-backup.sqlite"
            create_fixture(path)

            self.assertEqual(inventory(path)["integrity_check"], ["ok"])
            assert_file_can_be_renamed(self, path)

            backup(path, backup_path)
            assert_file_can_be_renamed(self, path)
            assert_file_can_be_renamed(self, backup_path)

            generated_backup = backfill(path)
            assert_file_can_be_renamed(self, path)
            assert_file_can_be_renamed(self, generated_backup)

    def test_failed_reconciliation_rolls_back_and_releases_database_handles(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            mapping = Path(directory) / "mapping.csv"
            create_fixture(path)
            backfill(path)
            export_reconciliation(path, mapping)
            with mapping.open(newline="", encoding="utf-8") as stream:
                rows = list(csv.DictReader(stream))
            order = next(row for row in rows if row["entity_type"] == "orders")
            order["edge_sync_id"] = "00000000-0000-4000-8000-000000000099"
            order["cloud_id"] = "77"
            order["cloud_sync_id"] = "00000000-0000-4000-8000-000000000077"
            order["canonical_sync_id"] = "00000000-0000-4000-8000-000000000099"
            order["decision"] = "MATCHED"
            with mapping.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)

            with self.assertRaisesRegex(ValueError, "changed since reconciliation export"):
                apply_local_reconciliation(path, mapping)

            with closing_sqlite(path) as db:
                self.assertEqual(
                    db.execute(
                        "SELECT state_value FROM sync_state WHERE state_key='reconciliation_mode'"
                    ).fetchone()[0],
                    "false",
                )
            backups = list(Path(directory).glob("edge.sqlite.m28-reconcile-backup-*"))
            self.assertEqual(len(backups), 1)
            assert_file_can_be_renamed(self, path)
            assert_file_can_be_renamed(self, backups[0])


def closing_sqlite(path: Path):
    """Test helper returning a context-managed SQLite connection."""
    return closing(sqlite3.connect(path))


def assert_file_can_be_renamed(test: unittest.TestCase, path: Path) -> None:
    """Prove no SQLite handle keeps this file open (required on Windows)."""
    renamed = path.with_name(f"{path.name}.released")
    path.rename(renamed)
    renamed.rename(path)


def list_tables_for_test(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }


if __name__ == "__main__":
    unittest.main()
