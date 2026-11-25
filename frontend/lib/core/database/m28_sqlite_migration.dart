import 'dart:math';

import 'package:sqflite/sqflite.dart';

import '../platform/io.dart';

/// Additive local schema foundation for M28 Phase 2.
///
/// This migration preserves all integer primary and foreign keys. It creates
/// UUID sidecars and metadata only; it does not create an outbox or run sync.
class M28SqliteMigration {
  M28SqliteMigration._();

  static const entityTables = <String>[
    'restaurants',
    'users',
    'customers',
    'tables',
    'categories',
    'products',
    'orders',
    'order_items',
    'kot',
    'order_status_logs',
    'payments',
    'bookings',
    'inventory',
  ];

  static const _mutableTables = <String>[
    'restaurants',
    'tables',
    'customers',
    'categories',
    'products',
    'orders',
    'order_items',
    'kot',
    'bookings',
    'inventory',
  ];

  static const _timestampTables = <String>[
    'restaurants',
    'users',
    'customers',
    'tables',
    'categories',
    'products',
    'orders',
    'order_items',
    'kot',
    'bookings',
    'inventory',
  ];

  /// Creates a consistent SQLite backup before an on-disk M28 upgrade.
  /// `VACUUM INTO` reads committed content including WAL state and requires a
  /// new destination file. Failure blocks the schema upgrade.
  static Future<String?> backupBeforeUpgrade(String path) async {
    final sourceFile = File(path);
    if (!await sourceFile.exists()) return null;

    final probe = await openDatabase(path);
    try {
      final version = await probe.getVersion();
      final tables = await probe.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
      );
      if (version >= 8 || tables.isEmpty) return null;

      final stamp = DateTime.now().toUtc().microsecondsSinceEpoch;
      final backupPath = '$path.m28-backup-$stamp.sqlite';
      final escapedPath = backupPath.replaceAll("'", "''");
      await probe.execute("VACUUM INTO '$escapedPath'");

      final backup = await openDatabase(backupPath, readOnly: true);
      try {
        final integrity = await backup.rawQuery('PRAGMA integrity_check');
        if (integrity.isEmpty || integrity.first.values.first != 'ok') {
          throw StateError('M28 SQLite backup failed integrity_check');
        }
      } finally {
        await backup.close();
      }
      return backupPath;
    } finally {
      await probe.close();
    }
  }

  static Future<void> apply(DatabaseExecutor db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS sync_outbox (
        change_id TEXT PRIMARY KEY,
        entity_type TEXT NOT NULL CHECK (entity_type IN ('orders','order_items')),
        entity_sync_id TEXT NOT NULL,
        operation TEXT NOT NULL CHECK (operation IN ('UPSERT','DELETE')),
        tenant_sync_id TEXT,
        branch_sync_id TEXT,
        entity_version INTEGER NOT NULL,
        payload_json TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING'
          CHECK (status IN ('PENDING','IN_FLIGHT','RETRY','ACKED','CONFLICT')),
        attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
        in_flight_at TEXT,
        next_attempt_at TEXT NOT NULL,
        created_at TEXT NOT NULL,
        last_error TEXT,
        acknowledged_at TEXT
      )
    ''');
    await db.execute('''
      CREATE INDEX IF NOT EXISTS ix_sync_outbox_ready
      ON sync_outbox(status, next_attempt_at, created_at)
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS sync_identity_map (
        entity_type TEXT NOT NULL,
        sync_id TEXT NOT NULL,
        edge_id INTEGER,
        cloud_id INTEGER,
        mapping_status TEXT NOT NULL DEFAULT 'UNRECONCILED'
          CHECK (mapping_status IN ('UNRECONCILED','MATCHED','EDGE_ONLY','CLOUD_ONLY','REVIEW_REQUIRED')),
        resolution_note TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (entity_type, sync_id),
        CHECK (edge_id IS NOT NULL OR cloud_id IS NOT NULL)
      )
    ''');
    await db.execute('''
      CREATE UNIQUE INDEX IF NOT EXISTS ux_sync_identity_map_edge
      ON sync_identity_map(entity_type, edge_id) WHERE edge_id IS NOT NULL
    ''');
    await db.execute('''
      CREATE UNIQUE INDEX IF NOT EXISTS ux_sync_identity_map_cloud
      ON sync_identity_map(entity_type, cloud_id) WHERE cloud_id IS NOT NULL
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS sync_state (
        state_key TEXT PRIMARY KEY,
        state_value TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS sync_cursors (
        stream_key TEXT PRIMARY KEY,
        cursor_value TEXT NOT NULL DEFAULT '',
        updated_at TEXT NOT NULL
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS sync_device_state (
        device_id TEXT PRIMARY KEY,
        tenant_sync_id TEXT,
        branch_sync_id TEXT,
        sync_epoch INTEGER NOT NULL DEFAULT 1 CHECK (sync_epoch > 0),
        schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
        binding_state TEXT NOT NULL DEFAULT 'UNBOUND'
          CHECK (binding_state IN ('UNBOUND','BOUND','REVOKED')),
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        CHECK (binding_state <> 'BOUND' OR
          (tenant_sync_id IS NOT NULL AND branch_sync_id IS NOT NULL))
      )
    ''');

    // Cloud catalog rows carry these category attributes. Older Edge schemas
    // created categories with only name and restaurant_id, so add the fields
    // before SyncEngine applies a pulled catalog page.
    if (await _tableExists(db, 'categories')) {
      final categoryColumns = await _columns(db, 'categories');
      if (!categoryColumns.contains('sort_order')) {
        await db.execute(
          'ALTER TABLE categories ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0',
        );
      }
      if (!categoryColumns.contains('is_active')) {
        await db.execute(
          'ALTER TABLE categories ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1',
        );
      }
    }

    for (final table in entityTables) {
      if (!await _tableExists(db, table)) continue;
      final columns = await _columns(db, table);
      if (!columns.contains('sync_id')) {
        await db.execute('ALTER TABLE "$table" ADD COLUMN sync_id TEXT');
      }
      if (_mutableTables.contains(table) && !columns.contains('version')) {
        await db.execute(
          'ALTER TABLE "$table" ADD COLUMN version INTEGER NOT NULL DEFAULT 1',
        );
      }
      if ((table != 'order_status_logs' && table != 'payments') &&
          !columns.contains('updated_at')) {
        // SQLite cannot add a non-constant expression as an ALTER default.
        // Existing rows are backfilled below; triggers populate new rows.
        await db.execute('ALTER TABLE "$table" ADD COLUMN updated_at TEXT');
      }
      if (table == 'payments') {
        if (!columns.contains('event_type')) {
          await db.execute(
            "ALTER TABLE payments ADD COLUMN event_type TEXT NOT NULL DEFAULT 'PAYMENT'",
          );
        }
        if (!columns.contains('reversal_of_sync_id')) {
          await db.execute(
            'ALTER TABLE payments ADD COLUMN reversal_of_sync_id TEXT',
          );
        }
        if (!columns.contains('currency_code')) {
          await db.execute(
            'ALTER TABLE payments ADD COLUMN currency_code TEXT',
          );
        }
        if (!columns.contains('idempotency_key')) {
          await db.execute(
            'ALTER TABLE payments ADD COLUMN idempotency_key TEXT',
          );
        }
      }

      final rows = await db.query(table, columns: ['id', 'sync_id']);
      for (final row in rows) {
        final id = row['id'];
        final rawSyncId = row['sync_id']?.toString();
        final syncId = rawSyncId == null || rawSyncId.isEmpty
            ? _newUuid()
            : rawSyncId;
        if (rawSyncId == null || rawSyncId.isEmpty) {
          await db.update(
            table,
            {'sync_id': syncId},
            where: 'id = ?',
            whereArgs: [id],
          );
        }
        if (table == 'payments') {
          await db.update(
            'payments',
            {'idempotency_key': syncId},
            where: 'id = ? AND idempotency_key IS NULL',
            whereArgs: [id],
          );
        }
        if (table != 'order_status_logs' && table != 'payments') {
          await db.update(
            table,
            {'updated_at': DateTime.now().toUtc().toIso8601String()},
            where: 'id = ? AND updated_at IS NULL',
            whereArgs: [id],
          );
        }
        await db.insert('sync_identity_map', {
          'entity_type': table,
          'sync_id': syncId,
          'edge_id': id,
          'mapping_status': 'UNRECONCILED',
          'created_at': DateTime.now().toUtc().toIso8601String(),
          'updated_at': DateTime.now().toUtc().toIso8601String(),
        }, conflictAlgorithm: ConflictAlgorithm.ignore);
      }
      await db.execute(
        'CREATE UNIQUE INDEX IF NOT EXISTS "ux_m28_${table}_sync_id" '
        'ON "$table" (sync_id)',
      );
      if (table == 'payments') {
        await db.execute('''
          CREATE UNIQUE INDEX IF NOT EXISTS ux_m28_payments_idempotency
          ON payments(idempotency_key) WHERE idempotency_key IS NOT NULL
        ''');
      }
      await db.execute(
        'CREATE TRIGGER IF NOT EXISTS "trg_m28_${table}_sync_id_immutable" '
        'BEFORE UPDATE OF sync_id ON "$table" '
        "WHEN OLD.sync_id IS NOT NULL AND NEW.sync_id IS NOT OLD.sync_id "
        "AND COALESCE((SELECT state_value FROM sync_state WHERE state_key='reconciliation_mode'),'false') <> 'true' "
        "BEGIN SELECT RAISE(ABORT, 'sync_id is immutable'); END",
      );
      await _createInsertIdentityTrigger(db, table);
      if (table == 'orders' || table == 'order_items') {
        await _createOutboxUpdateTrigger(db, table);
      }
      if (_mutableTables.contains(table)) {
        await _createVersionTriggers(db, table);
      } else if (_timestampTables.contains(table)) {
        await _createTimestampTriggers(db, table);
      }
    }

    final now = DateTime.now().toUtc().toIso8601String();
    await db.insert('sync_state', {
      'state_key': 'schema_version',
      'state_value': '1',
      'updated_at': now,
    }, conflictAlgorithm: ConflictAlgorithm.ignore);
    await db.insert('sync_state', {
      'state_key': 'reconciliation_mode',
      'state_value': 'false',
      'updated_at': now,
    }, conflictAlgorithm: ConflictAlgorithm.ignore);
    final localDevice = await db.query('sync_device_state', limit: 1);
    if (localDevice.isEmpty) {
      await db.insert('sync_device_state', {
        'device_id': _newUuid(),
        'sync_epoch': 1,
        'schema_version': 1,
        'binding_state': 'UNBOUND',
        'created_at': now,
        'updated_at': now,
      });
    }
  }

  static Future<void> _createVersionTriggers(
    DatabaseExecutor db,
    String table,
  ) async {
    await db.execute('''
      CREATE TRIGGER IF NOT EXISTS "trg_m28_${table}_insert_timestamp"
      AFTER INSERT ON "$table"
      WHEN NEW.updated_at IS NULL
      BEGIN
        UPDATE "$table" SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = NEW.id;
      END
    ''');
    await db.execute('''
      CREATE TRIGGER IF NOT EXISTS "trg_m28_${table}_version_timestamp"
      AFTER UPDATE ON "$table"
      WHEN OLD.updated_at IS NOT NULL
        AND NEW.sync_id IS OLD.sync_id
        AND NEW.updated_at IS OLD.updated_at
      BEGIN
        UPDATE "$table"
        SET version = MAX(OLD.version + 1, NEW.version),
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = NEW.id;
      END
    ''');
  }

  static Future<void> _createTimestampTriggers(
    DatabaseExecutor db,
    String table,
  ) async {
    await db.execute('''
      CREATE TRIGGER IF NOT EXISTS "trg_m28_${table}_update_timestamp"
      AFTER UPDATE ON "$table"
      WHEN NEW.updated_at IS OLD.updated_at
      BEGIN
        UPDATE "$table" SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = NEW.id;
      END
    ''');
  }

  static Future<void> _createInsertIdentityTrigger(
    DatabaseExecutor db,
    String table,
  ) async {
    final generatedUuid =
        "lower(hex(randomblob(4))) || '-' || "
        "lower(hex(randomblob(2))) || '-4' || "
        "substr(lower(hex(randomblob(2))), 2) || '-' || "
        "substr('89ab', (abs(random()) % 4) + 1, 1) || "
        "substr(lower(hex(randomblob(2))), 2) || '-' || "
        "lower(hex(randomblob(6)))";
    await db.execute('''
      CREATE TRIGGER IF NOT EXISTS "trg_m28_${table}_identity_after_insert"
      AFTER INSERT ON "$table"
      BEGIN
        UPDATE "$table"
        SET sync_id = COALESCE(NEW.sync_id, $generatedUuid)
            ${_timestampTables.contains(table) ? ", updated_at = COALESCE(NEW.updated_at, strftime('%Y-%m-%dT%H:%M:%fZ','now'))" : ''}
        WHERE id = NEW.id;
        ${table == 'payments' ? "UPDATE payments SET idempotency_key = COALESCE(NEW.idempotency_key, sync_id) WHERE id = NEW.id;" : ''}
        INSERT OR IGNORE INTO sync_identity_map
          (entity_type, sync_id, edge_id, mapping_status, created_at, updated_at)
        SELECT '$table', sync_id, id, 'UNRECONCILED',
          strftime('%Y-%m-%dT%H:%M:%fZ','now'),
          strftime('%Y-%m-%dT%H:%M:%fZ','now')
        FROM "$table" WHERE id = NEW.id AND sync_id IS NOT NULL;
        ${_outboxInsertSql(table, 'NEW', 'UPSERT')}
      END
    ''');
  }

  static Future<void> _createOutboxUpdateTrigger(
    DatabaseExecutor db,
    String table,
  ) async {
    final watchedColumns = table == 'orders'
        ? "total_amount, status, type, order_time, notes, restaurant_id, table_id, customer_id, waiter_id, discount_amount, customer_name, customer_phone, guest_count"
        : "order_id, product_id, quantity, price, notes, status, kot_id";
    await db.execute('''
      CREATE TRIGGER IF NOT EXISTS "trg_m28_${table}_outbox_update"
      AFTER UPDATE ON "$table"
      WHEN ${watchedColumns.split(', ').map((column) => 'OLD.$column IS NOT NEW.$column').join(' OR ')}
      BEGIN
        ${_outboxInsertSql(table, 'NEW', 'UPSERT')}
      END
    ''');
  }

  static String _outboxInsertSql(String table, String row, String operation) {
    if (table != 'orders' && table != 'order_items') return '';
    final entitySyncId = "(SELECT sync_id FROM $table WHERE id = $row.id)";
    final branchId = table == 'orders'
        ? '$row.restaurant_id'
        : '(SELECT restaurant_id FROM orders WHERE id = $row.order_id)';
    final branchSyncId =
        "(SELECT sync_id FROM restaurants WHERE id = $branchId)";
    final payload = table == 'orders'
        ? "json_object('sync_id',$entitySyncId,'local_id',$row.id,'restaurant_local_id',$row.restaurant_id,'table_local_id',$row.table_id,'customer_local_id',$row.customer_id,'waiter_local_id',$row.waiter_id,'total_amount',$row.total_amount,'status',$row.status,'type',$row.type,'order_time',$row.order_time,'notes',$row.notes,'discount_amount',$row.discount_amount,'customer_name',$row.customer_name,'customer_phone',$row.customer_phone,'guest_count',$row.guest_count,'version',$row.version,'updated_at',$row.updated_at)"
        : "json_object('sync_id',$entitySyncId,'local_id',$row.id,'order_sync_id',(SELECT sync_id FROM orders WHERE id=$row.order_id),'order_local_id',$row.order_id,'product_sync_id',(SELECT sync_id FROM products WHERE id=$row.product_id),'product_local_id',$row.product_id,'product_version',(SELECT version FROM products WHERE id=$row.product_id),'quantity',$row.quantity,'price',$row.price,'notes',$row.notes,'status',$row.status,'kot_id',$row.kot_id,'version',$row.version)";
    final uuid =
        "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', (abs(random()) % 4) + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))";
    return '''
      INSERT INTO sync_outbox(change_id,entity_type,entity_sync_id,operation,tenant_sync_id,branch_sync_id,
        entity_version,payload_json,next_attempt_at,created_at)
      SELECT $uuid,'$table',$entitySyncId,'$operation',
        (SELECT tenant_sync_id FROM sync_device_state LIMIT 1),$branchSyncId,
        COALESCE($row.version,1),$payload,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now')
      WHERE $entitySyncId IS NOT NULL;
    ''';
  }

  static Future<bool> _tableExists(DatabaseExecutor db, String table) async {
    final rows = await db.rawQuery(
      "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
      [table],
    );
    return rows.isNotEmpty;
  }

  static Future<Set<String>> _columns(DatabaseExecutor db, String table) async {
    final rows = await db.rawQuery('PRAGMA table_info("$table")');
    return rows.map((row) => row['name'].toString()).toSet();
  }

  static String _newUuid() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes
        .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
        .join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
        '${hex.substring(12, 16)}-${hex.substring(16, 20)}-'
        '${hex.substring(20)}';
  }
}
