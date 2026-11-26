import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:dine_master/repositories/outbox_repository.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  test(
    'claims once and preserves retry UUID after a temporary failure',
    () async {
      final db = await databaseFactory.openDatabase(inMemoryDatabasePath);
      addTearDown(db.close);
      await db.execute('''CREATE TABLE sync_outbox (
      change_id TEXT PRIMARY KEY, entity_type TEXT, entity_sync_id TEXT, operation TEXT,
      tenant_sync_id TEXT, branch_sync_id TEXT, entity_version INTEGER, payload_json TEXT,
      status TEXT, attempts INTEGER, in_flight_at TEXT, next_attempt_at TEXT, created_at TEXT,
      last_error TEXT, acknowledged_at TEXT)''');
      final id = '00000000-0000-4000-8000-000000000001';
      final now = DateTime.now()
          .toUtc()
          .subtract(const Duration(seconds: 1))
          .toIso8601String();
      await db.insert('sync_outbox', {
        'change_id': id,
        'entity_type': 'orders',
        'entity_sync_id': id,
        'operation': 'UPSERT',
        'entity_version': 1,
        'payload_json': '{}',
        'status': 'PENDING',
        'attempts': 0,
        'next_attempt_at': now,
        'created_at': now,
      });

      final outbox = OutboxRepository(db);
      final claimed = await outbox.claimReady();
      expect(claimed, hasLength(1));
      expect(claimed.single['change_id'], id);
      expect((await outbox.claimReady()), isEmpty);
      await outbox.retry(id, 'temporary network failure');
      final delayed = (await db.query('sync_outbox')).single;
      expect(delayed['status'], 'RETRY');
      expect(delayed['change_id'], id);
      expect(delayed['attempts'], 1);
      expect(delayed['last_error'], 'temporary network failure');
    },
  );

  test('pending changes survive a database close and reopen', () async {
    final directory = await Directory.systemTemp.createTemp('m28-outbox-');
    late Database db;
    addTearDown(() async {
      await db.close();
      await directory.delete(recursive: true);
    });
    final path = '${directory.path}${Platform.pathSeparator}edge.sqlite';
    db = await databaseFactory.openDatabase(path);
    await _createOutbox(db);
    final changeId = '00000000-0000-4000-8000-000000000002';
    final now = DateTime.now()
        .toUtc()
        .subtract(const Duration(seconds: 1))
        .toIso8601String();
    await db.insert('sync_outbox', {
      'change_id': changeId,
      'entity_type': 'orders',
      'entity_sync_id': changeId,
      'operation': 'UPSERT',
      'entity_version': 1,
      'payload_json': '{}',
      'status': 'PENDING',
      'attempts': 0,
      'next_attempt_at': now,
      'created_at': now,
    });
    await db.close();

    db = await databaseFactory.openDatabase(path);
    final resumed = await OutboxRepository(db).claimReady();
    expect(resumed, hasLength(1));
    expect(resumed.single['change_id'], changeId);
    expect(resumed.single['status'], 'IN_FLIGHT');
  });
}

Future<void> _createOutbox(Database db) =>
    db.execute('''CREATE TABLE sync_outbox (
  change_id TEXT PRIMARY KEY, entity_type TEXT, entity_sync_id TEXT, operation TEXT,
  tenant_sync_id TEXT, branch_sync_id TEXT, entity_version INTEGER, payload_json TEXT,
  status TEXT, attempts INTEGER, in_flight_at TEXT, next_attempt_at TEXT, created_at TEXT,
  last_error TEXT, acknowledged_at TEXT)''');
