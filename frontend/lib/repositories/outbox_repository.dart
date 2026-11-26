import 'dart:convert';
import 'dart:math' as math;

import 'package:sqflite/sqflite.dart';

/// Durable SQLite outbox operations. Each state change is transactional and
/// retains the original change UUID across retries.
class OutboxRepository {
  const OutboxRepository(this.database);

  final Database database;

  Future<List<Map<String, Object?>>> claimReady({int limit = 50}) async {
    if (limit < 1 || limit > 200) {
      throw ArgumentError.value(limit, 'limit', 'must be between 1 and 200');
    }
    return database.transaction((txn) async {
      final now = DateTime.now().toUtc().toIso8601String();
      final staleLease = DateTime.now()
          .toUtc()
          .subtract(const Duration(minutes: 5))
          .toIso8601String();
      await txn.rawUpdate(
        "UPDATE sync_outbox SET status='RETRY', in_flight_at=NULL, "
        'next_attempt_at=? WHERE status=\'IN_FLIGHT\' AND in_flight_at <= ?',
        [now, staleLease],
      );
      final rows = await txn.query(
        'sync_outbox',
        where: "status IN ('PENDING','RETRY') AND next_attempt_at <= ?",
        whereArgs: [now],
        orderBy: 'created_at, change_id',
        limit: limit,
      );
      final claimed = <Map<String, Object?>>[];
      for (final row in rows) {
        final count = await txn.update(
          'sync_outbox',
          {
            'status': 'IN_FLIGHT',
            'attempts': (row['attempts'] as int? ?? 0) + 1,
            'in_flight_at': now,
            'last_error': null,
          },
          where: "change_id = ? AND status IN ('PENDING','RETRY')",
          whereArgs: [row['change_id']],
        );
        if (count == 1) {
          claimed.add(
            Map<String, Object?>.from(row)
              ..['status'] = 'IN_FLIGHT'
              ..['attempts'] = (row['attempts'] as int? ?? 0) + 1,
          );
        }
      }
      return claimed;
    });
  }

  Future<void> acknowledge(String changeId) async {
    final count = await database.update(
      'sync_outbox',
      {
        'status': 'ACKED',
        'acknowledged_at': DateTime.now().toUtc().toIso8601String(),
        'last_error': null,
        'in_flight_at': null,
      },
      where: "change_id = ? AND status = 'IN_FLIGHT'",
      whereArgs: [changeId],
    );
    if (count != 1) throw StateError('Outbox item is not currently in flight');
  }

  Future<void> markConflict(String changeId, Object reason) async {
    final count = await database.update(
      'sync_outbox',
      {
        'status': 'CONFLICT',
        'last_error': reason.toString().substring(
          0,
          math.min(1000, reason.toString().length).toInt(),
        ),
        'in_flight_at': null,
      },
      where: "change_id=? AND status='IN_FLIGHT'",
      whereArgs: [changeId],
    );
    if (count != 1) throw StateError('Outbox item is not currently in flight');
  }

  Future<void> retry(String changeId, Object error) async {
    await database.transaction((txn) async {
      final rows = await txn.query(
        'sync_outbox',
        columns: ['attempts'],
        where: "change_id = ? AND status = 'IN_FLIGHT'",
        whereArgs: [changeId],
        limit: 1,
      );
      if (rows.isEmpty) {
        throw StateError('Outbox item is not currently in flight');
      }
      final attempts = rows.single['attempts'] as int? ?? 1;
      final delaySeconds = math.min(3600, 1 << math.min(attempts, 12)).toInt();
      await txn.update(
        'sync_outbox',
        {
          'status': 'RETRY',
          'in_flight_at': null,
          'next_attempt_at': DateTime.now()
              .toUtc()
              .add(Duration(seconds: delaySeconds))
              .toIso8601String(),
          'last_error': error.toString().substring(
            0,
            math.min(1000, error.toString().length).toInt(),
          ),
        },
        where: 'change_id = ?',
        whereArgs: [changeId],
      );
    });
  }

  Future<Map<String, Object?>> decodePayload(Map<String, Object?> row) async {
    final decoded = jsonDecode(row['payload_json'] as String);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Outbox payload must be a JSON object');
    }
    return decoded;
  }
}
