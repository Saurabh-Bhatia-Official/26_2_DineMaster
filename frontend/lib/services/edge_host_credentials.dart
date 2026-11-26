import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Host credentials are kept apart from cloud staff JWTs and only in OS-backed
/// secure storage. No preferences/base64 fallback is provided.
class EdgeHostCredentials {
  EdgeHostCredentials._();
  static const _storage = FlutterSecureStorage();
  static const _keys = <String>[
    'm28.edge.host_id',
    'm28.edge.tenant_id',
    'm28.edge.branch_id',
    'm28.edge.branch_sync_id',
    'm28.edge.sync_epoch',
    'm28.edge.access_token',
    'm28.edge.access_expiry',
    'm28.edge.refresh_token',
    'm28.edge.private_key_components',
    'm28.edge.public_key_pem',
  ];

  static Future<String?> accessToken() => _storage.read(key: _keys[5]);
  static Future<String?> refreshToken() => _storage.read(key: _keys[7]);
  static Future<String?> hostId() => _storage.read(key: _keys[0]);
  static Future<String?> tenantId() => _storage.read(key: _keys[1]);
  static Future<String?> branchSyncId() => _storage.read(key: _keys[3]);
  static Future<int> syncEpoch() async =>
      int.tryParse(await _storage.read(key: _keys[4]) ?? '') ?? 0;
  static Future<DateTime?> accessExpiry() async =>
      DateTime.tryParse(await _storage.read(key: _keys[6]) ?? '');

  static Future<void> save({
    required String hostId,
    required String tenantId,
    required String branchId,
    required String branchSyncId,
    required int epoch,
    required String accessToken,
    required DateTime accessExpiresAt,
    required String refreshToken,
    required Map<String, String> privateKeyComponents,
    required String publicKeyPem,
  }) async {
    final values = <String, String>{
      _keys[0]: hostId,
      _keys[1]: tenantId,
      _keys[2]: branchId,
      _keys[3]: branchSyncId,
      _keys[4]: '$epoch',
      _keys[5]: accessToken,
      _keys[6]: accessExpiresAt.toUtc().toIso8601String(),
      _keys[7]: refreshToken,
      _keys[8]: jsonEncode(privateKeyComponents),
      _keys[9]: publicKeyPem,
    };
    for (final entry in values.entries) {
      await _storage.write(key: entry.key, value: entry.value);
    }
  }

  static Future<Map<String, String>?> privateKeyComponents() async {
    final value = await _storage.read(key: _keys[8]);
    if (value == null) return null;
    final decoded = jsonDecode(value);
    if (decoded is! Map) return null;
    return decoded.map(
      (key, value) => MapEntry(key.toString(), value.toString()),
    );
  }

  static Future<String?> publicKeyPem() => _storage.read(key: _keys[9]);

  static Future<void> updateTokens({
    required String accessToken,
    required DateTime expiresAt,
    required String refreshToken,
  }) async {
    await _storage.write(key: _keys[5], value: accessToken);
    await _storage.write(
      key: _keys[6],
      value: expiresAt.toUtc().toIso8601String(),
    );
    await _storage.write(key: _keys[7], value: refreshToken);
  }

  static Future<void> clear() async {
    for (final key in _keys) {
      await _storage.delete(key: key);
    }
  }
}
