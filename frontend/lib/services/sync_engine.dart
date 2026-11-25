import 'dart:async';
import 'dart:convert';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:sqflite/sqflite.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/database/database_helper.dart';
import '../repositories/outbox_repository.dart';
import 'edge_host_credentials.dart';

enum M28SyncStatus {
  disabled,
  notRegistered,
  offline,
  idle,
  syncing,
  authRequired,
  error,
}

/// Single-host development sync runner. All transport URLs must be HTTPS and
/// use the platform's normal certificate validation.
class SyncEngine {
  SyncEngine._();
  static final SyncEngine instance = SyncEngine._();
  final ValueNotifier<M28SyncStatus> status = ValueNotifier(
    M28SyncStatus.disabled,
  );
  final ValueNotifier<String?> lastError = ValueNotifier(null);
  final Connectivity _connectivity = Connectivity();
  final http.Client _client = http.Client();
  StreamSubscription<List<ConnectivityResult>>? _connectivitySubscription;
  Timer? _timer;
  bool _started = false;
  bool _running = false;

  Future<void> start() async {
    if (_started || kIsWeb) return;
    final prefs = await SharedPreferences.getInstance();
    if (!(prefs.getBool('m28_sync_enabled') ?? false)) {
      status.value = M28SyncStatus.disabled;
      return;
    }
    if (await EdgeHostCredentials.hostId() == null) {
      status.value = M28SyncStatus.notRegistered;
      return;
    }
    _started = true;
    _connectivitySubscription = _connectivity.onConnectivityChanged.listen((
      states,
    ) {
      if (states.any((state) => state != ConnectivityResult.none)) {
        unawaited(syncOnce());
      } else {
        status.value = M28SyncStatus.offline;
      }
    });
    _timer = Timer.periodic(
      const Duration(minutes: 5),
      (_) => unawaited(syncOnce()),
    );
    unawaited(syncOnce());
  }

  Future<void> stop() async {
    _timer?.cancel();
    _timer = null;
    await _connectivitySubscription?.cancel();
    _connectivitySubscription = null;
    _started = false;
    status.value = M28SyncStatus.disabled;
  }

  Future<void> syncOnce() async {
    if (kIsWeb || _running) return;
    final prefs = await SharedPreferences.getInstance();
    if (!(prefs.getBool('m28_sync_enabled') ?? false)) return;
    if (await EdgeHostCredentials.hostId() == null) {
      status.value = M28SyncStatus.notRegistered;
      return;
    }
    _running = true;
    status.value = M28SyncStatus.syncing;
    lastError.value = null;
    try {
      final db = await DatabaseHelper.instance.database;
      final branchSyncId = await EdgeHostCredentials.branchSyncId();
      final tenantId = await EdgeHostCredentials.tenantId();
      final binding = await db.query(
        'sync_device_state',
        columns: [
          'binding_state',
          'tenant_sync_id',
          'branch_sync_id',
          'sync_epoch',
        ],
        limit: 1,
      );
      if (binding.isEmpty ||
          binding.single['binding_state'] != 'BOUND' ||
          binding.single['tenant_sync_id'] != tenantId ||
          binding.single['branch_sync_id'] != branchSyncId ||
          (binding.single['sync_epoch'] as int) !=
              await EdgeHostCredentials.syncEpoch()) {
        status.value = M28SyncStatus.authRequired;
        return;
      }
      await _pushOutbox(db);
      await _pullMenu(db);
      await _writeState(
        db,
        'last_success_at',
        DateTime.now().toUtc().toIso8601String(),
      );
      await _writeState(db, 'last_status', 'OK');
      status.value = M28SyncStatus.idle;
    } on _AuthExpired catch (error) {
      lastError.value = error.message;
      status.value = M28SyncStatus.authRequired;
      try {
        await _writeState(
          await DatabaseHelper.instance.database,
          'last_status',
          'AUTH_REQUIRED',
        );
      } catch (_) {}
    } catch (error) {
      lastError.value = error.toString();
      status.value = M28SyncStatus.error;
      try {
        await _writeState(
          await DatabaseHelper.instance.database,
          'last_status',
          'ERROR',
        );
      } catch (_) {}
      final results = await _connectivity.checkConnectivity();
      if (results.every((result) => result == ConnectivityResult.none)) {
        status.value = M28SyncStatus.offline;
      }
    } finally {
      _running = false;
    }
  }

  Future<void> _pushOutbox(dynamic db) async {
    final repository = OutboxRepository(db);
    while (true) {
      final changes = await repository.claimReady(limit: 50);
      if (changes.isEmpty) return;
      try {
        final response = await _request(
          'POST',
          '/api/v1/sync/push',
          body: {
            'changes': changes
                .map(
                  (row) => {
                    'changeId': row['change_id'],
                    'entityType': row['entity_type'],
                    'entitySyncId': row['entity_sync_id'],
                    'operation': row['operation'],
                    'payload': jsonDecode(row['payload_json'] as String),
                  },
                )
                .toList(),
          },
        );
        final resultRows = (response['results'] as List).cast<Map>();
        final byId = {
          for (final item in resultRows) item['changeId'].toString(): item,
        };
        for (final change in changes) {
          final id = change['change_id'].toString();
          final result = byId[id];
          if (result == null) {
            await repository.retry(
              id,
              'Server response omitted change receipt',
            );
          } else if (result['result'] == 'ACCEPTED') {
            await repository.acknowledge(id);
          } else {
            await repository.markConflict(
              id,
              result['detail'] ?? result['result'] ?? 'Sync conflict',
            );
          }
        }
      } catch (error) {
        for (final change in changes) {
          try {
            await repository.retry(change['change_id'].toString(), error);
          } catch (_) {}
        }
        rethrow;
      }
    }
  }

  Future<void> _pullMenu(dynamic db) async {
    for (var page = 0; page < 10; page++) {
      final cursorRows = await db.query(
        'sync_cursors',
        columns: ['cursor_value'],
        where: 'stream_key=?',
        whereArgs: ['menu'],
        limit: 1,
      );
      final cursor = cursorRows.isEmpty
          ? ''
          : cursorRows.single['cursor_value'] as String;
      final uri = await _uri('/api/v1/sync/pull').then(
        (base) => base.replace(
          queryParameters: {'stream': 'menu', 'cursor': cursor, 'limit': '100'},
        ),
      );
      final response = await _requestUri('GET', uri);
      final items = (response['items'] as List).cast<Map>();
      final nextCursor = response['nextCursor']?.toString();
      if (nextCursor == null || nextCursor.isEmpty) {
        throw const FormatException('Sync pull cursor missing');
      }
      await db.transaction((txn) async {
        for (final item in items) {
          await _applyCatalogItem(txn, Map<String, dynamic>.from(item));
        }
        await txn.insert('sync_cursors', {
          'stream_key': 'menu',
          'cursor_value': nextCursor,
          'updated_at': DateTime.now().toUtc().toIso8601String(),
        }, conflictAlgorithm: ConflictAlgorithm.replace);
        await txn.update(
          'sync_state',
          {
            'state_value': 'OK',
            'updated_at': DateTime.now().toUtc().toIso8601String(),
          },
          where: 'state_key=?',
          whereArgs: ['last_status'],
        );
      });
      await _request(
        'POST',
        '/api/v1/sync/pull/ack',
        body: {'cursor': nextCursor},
      );
      if (items.length < 100 || nextCursor == cursor) return;
    }
  }

  Future<void> _applyCatalogItem(dynamic txn, Map<String, dynamic> item) async {
    final type = item['entityType']?.toString();
    final syncId = item['syncId']?.toString();
    final cloudId = item['cloudId'];
    final data = Map<String, dynamic>.from(item['data'] as Map);
    if (syncId == null || (type != 'categories' && type != 'products')) {
      throw const FormatException('Unsupported catalog entity');
    }
    final found = await txn.query(
      type,
      columns: ['id'],
      where: 'sync_id=?',
      whereArgs: [syncId],
      limit: 1,
    );
    if (type == 'categories') {
      final values = {
        'name': data['name'],
        'sort_order': data['sort_order'] ?? 0,
        'is_active': data['is_active'] == true ? 1 : 0,
        'restaurant_id': await _localBranchId(txn),
        'version': item['version'],
        'updated_at': item['updatedAt'],
      };
      if (found.isEmpty) {
        await txn.insert('categories', {...values, 'sync_id': syncId});
      } else {
        await txn.update(
          'categories',
          values,
          where: 'id=?',
          whereArgs: [found.single['id']],
        );
      }
    } else {
      final values = <String, Object?>{
        'name': data['name'],
        'description': data['description'],
        'ingredients': data['ingredients'],
        'price': data['price'],
        'category': data['category'] ?? '',
        'is_veg': data['is_veg'] ?? 0,
        'image_path': data['image_path'],
        'is_available': data['is_available'] == true ? 1 : 0,
        'gst_percentage': data['gst_percentage'] ?? 0,
        'dietary_preferences': data['dietary_preferences'],
        'taste_preferences': data['taste_preferences'],
        'attributes': data['attributes'],
        'custom_dietary_notes': data['custom_dietary_notes'],
        'recipe_steps': data['recipe_steps'],
        'prep_time': _optionalInt(data['prep_time']),
        'cook_time': _optionalInt(data['cook_time']),
        'servings': _optionalInt(data['servings']),
        'difficulty': data['difficulty'],
        'video_path': data['video_path'],
        'restaurant_id': await _localBranchId(txn),
        'version': item['version'],
        'updated_at': item['updatedAt'],
      };
      if (found.isEmpty) {
        await txn.insert('products', {...values, 'sync_id': syncId});
      } else {
        await txn.update(
          'products',
          values,
          where: 'id=?',
          whereArgs: [found.single['id']],
        );
      }
    }
    final localRow = await txn.query(
      type,
      columns: ['id'],
      where: 'sync_id=?',
      whereArgs: [syncId],
      limit: 1,
    );
    if (localRow.isEmpty) throw StateError('Catalog row was not persisted');
    await txn.rawInsert(
      '''
      INSERT INTO sync_identity_map(entity_type,sync_id,edge_id,cloud_id,mapping_status,created_at,updated_at)
      VALUES(?,?,?,?, 'MATCHED', ?, ?)
      ON CONFLICT(entity_type,sync_id) DO UPDATE SET cloud_id=excluded.cloud_id,
        mapping_status=CASE WHEN sync_identity_map.edge_id IS NULL THEN 'CLOUD_ONLY' ELSE 'MATCHED' END,
        updated_at=excluded.updated_at
    ''',
      [
        type,
        syncId,
        localRow.single['id'],
        cloudId,
        DateTime.now().toUtc().toIso8601String(),
        DateTime.now().toUtc().toIso8601String(),
      ],
    );
  }

  Future<int> _localBranchId(dynamic txn) async {
    final branchSyncId = await EdgeHostCredentials.branchSyncId();
    final rows = await txn.query(
      'restaurants',
      columns: ['id'],
      where: 'sync_id=?',
      whereArgs: [branchSyncId],
      limit: 1,
    );
    if (rows.isEmpty) {
      throw StateError('Bound branch UUID is not reconciled locally');
    }
    return rows.single['id'] as int;
  }

  Future<Map<String, dynamic>> _request(
    String method,
    String path, {
    Object? body,
  }) async {
    final uri = await _uri(path);
    return _requestUri(method, uri, body: body);
  }

  Future<Map<String, dynamic>> _requestUri(
    String method,
    Uri uri, {
    Object? body,
  }) async {
    var token = await _accessToken();
    var response = await _send(method, uri, token, body);
    if (response.statusCode == 401) {
      await _rotate();
      token = await _accessToken();
      response = await _send(method, uri, token, body);
    }
    if (response.statusCode == 401 || response.statusCode == 403) {
      throw _AuthExpired('Host credential was rejected');
    }
    final decoded = response.body.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(response.body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError('Sync request failed (${response.statusCode})');
    }
    return decoded is Map
        ? Map<String, dynamic>.from(decoded)
        : <String, dynamic>{};
  }

  Future<http.Response> _send(
    String method,
    Uri uri,
    String token,
    Object? body,
  ) {
    final headers = {
      'Authorization': 'Bearer $token',
      if (body != null) 'Content-Type': 'application/json',
    };
    final encoded = body == null ? null : jsonEncode(body);
    return switch (method) {
      'GET' =>
        _client.get(uri, headers: headers).timeout(const Duration(seconds: 30)),
      'POST' =>
        _client
            .post(uri, headers: headers, body: encoded)
            .timeout(const Duration(seconds: 30)),
      _ => throw ArgumentError.value(method, 'method'),
    };
  }

  Future<String> _accessToken() async {
    final expiry = await EdgeHostCredentials.accessExpiry();
    final current = await EdgeHostCredentials.accessToken();
    if (current == null || current.isEmpty) {
      throw _AuthExpired('Host access credential is missing');
    }
    if (expiry == null ||
        expiry.isBefore(
          DateTime.now().toUtc().add(const Duration(seconds: 30)),
        )) {
      await _rotate();
      return (await EdgeHostCredentials.accessToken())!;
    }
    return current;
  }

  Future<void> _rotate() async {
    final refresh = await EdgeHostCredentials.refreshToken();
    if (refresh == null || refresh.isEmpty) {
      throw _AuthExpired('Host refresh credential is missing');
    }
    final uri = await _uri('/api/v1/auth/edge-host-credentials/rotate');
    final response = await _client
        .post(
          uri,
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'refreshToken': refresh}),
        )
        .timeout(const Duration(seconds: 20));
    if (response.statusCode != 200) {
      throw _AuthExpired('Host refresh credential was rejected');
    }
    final result = Map<String, dynamic>.from(jsonDecode(response.body) as Map);
    await EdgeHostCredentials.updateTokens(
      accessToken: result['accessToken'].toString(),
      expiresAt: DateTime.parse(result['accessExpiresAt'].toString()),
      refreshToken: result['refreshToken'].toString(),
    );
  }

  Future<Uri> _uri(String path) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString('backend_api_url')?.trim();
    final base = raw == null || raw.isEmpty ? null : Uri.tryParse(raw);
    if (base == null ||
        base.scheme != 'https' ||
        !base.hasAuthority ||
        base.userInfo.isNotEmpty) {
      throw StateError('Synchronization requires a configured HTTPS cloud URL');
    }
    return base.resolve(path);
  }

  Future<void> _writeState(dynamic db, String key, String value) async {
    await db.insert('sync_state', {
      'state_key': key,
      'state_value': value,
      'updated_at': DateTime.now().toUtc().toIso8601String(),
    }, conflictAlgorithm: ConflictAlgorithm.replace);
  }
}

class _AuthExpired implements Exception {
  const _AuthExpired(this.message);
  final String message;
}

int? _optionalInt(dynamic value) {
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value.trim());
  return null;
}
