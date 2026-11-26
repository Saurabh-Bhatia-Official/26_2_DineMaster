import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../core/database/database_helper.dart';
import '../core/api/api_config.dart';
import 'auth_token_storage.dart';
import 'edge_host_credentials.dart';
import 'edge_host_identity.dart';
import 'sync_engine.dart';

/// Single-host development enrollment. Requires an already authenticated cloud
/// staff session and an HTTPS API URL with normal certificate verification.
class EdgeHostProvisioning {
  EdgeHostProvisioning({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;

  Future<Map<String, dynamic>> activateBranch(int cloudBranchId) async {
    if (kIsWeb) {
      throw UnsupportedError('Flutter Web cannot register an Edge host');
    }
    if (cloudBranchId <= 0) {
      throw ArgumentError.value(cloudBranchId, 'cloudBranchId');
    }
    final uri = await _baseUri();
    final userToken = await AuthTokenStorage.readAccessToken();
    if (userToken == null || userToken.isEmpty) {
      throw StateError('Cloud staff sign-in is required');
    }
    final identity = EdgeHostIdentity.generate();

    final activationResponse = await _client
        .post(
          uri.resolve('/api/v1/auth/edge-host-activations'),
          headers: _jsonHeaders(userToken),
          body: jsonEncode({'branchId': cloudBranchId}),
        )
        .timeout(const Duration(seconds: 20));
    final activation = _decodeSuccess(activationResponse);
    final activationId = activation['activationId']?.toString();
    final activationCode = activation['activationCode']?.toString();
    final branchSyncId = activation['branchSyncId']?.toString();
    if (activationId == null ||
        activationCode == null ||
        branchSyncId == null) {
      throw const FormatException('Activation response is incomplete');
    }

    final localDb = await DatabaseHelper.instance.database;
    final localBranch = await localDb.query(
      'restaurants',
      columns: ['id'],
      where: 'sync_id=?',
      whereArgs: [branchSyncId],
      limit: 1,
    );
    if (localBranch.isEmpty) {
      throw StateError(
        'Local branch UUID has not been reconciled with the selected cloud branch',
      );
    }

    final nonceBytes = List<int>.generate(
      32,
      (_) => Random.secure().nextInt(256),
    );
    final nonce = base64UrlEncode(nonceBytes).replaceAll('=', '');
    final consumeResponse = await _client
        .post(
          uri.resolve('/api/v1/auth/edge-host-activations/consume'),
          headers: const {'Content-Type': 'application/json'},
          body: jsonEncode({
            'activationCode': activationCode,
            'publicKeyPem': identity.publicKeyPem,
            'nonce': nonce,
            'proofSignatureBase64': identity.signActivationProof(
              activationId: activationId,
              nonce: nonce,
            ),
          }),
        )
        .timeout(const Duration(seconds: 20));
    final registration = _decodeSuccess(consumeResponse);
    if (registration['credentialIssued'] != true) {
      throw StateError('Cloud did not issue a host credential');
    }

    await EdgeHostCredentials.save(
      hostId: registration['hostId'].toString(),
      tenantId: registration['tenantId'].toString(),
      branchId: registration['branchId'].toString(),
      branchSyncId: branchSyncId,
      epoch: (registration['syncEpoch'] as num).toInt(),
      accessToken: registration['accessToken'].toString(),
      accessExpiresAt: DateTime.parse(
        registration['accessExpiresAt'].toString(),
      ),
      refreshToken: registration['refreshToken'].toString(),
      privateKeyComponents: identity.privateComponents,
      publicKeyPem: identity.publicKeyPem,
    );
    await localDb.transaction((txn) async {
      await txn.update('sync_device_state', {
        'device_id': registration['hostId'].toString(),
        // Tenant UUID sidecars are not part of the current cloud schema. This
        // field stores the authenticated cloud tenant scope identifier only;
        // it is never used to infer cross-database row identity.
        'tenant_sync_id': registration['tenantId'].toString(),
        'branch_sync_id': branchSyncId,
        'sync_epoch': (registration['syncEpoch'] as num).toInt(),
        'binding_state': 'BOUND',
        'updated_at': DateTime.now().toUtc().toIso8601String(),
      });
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('m28_sync_enabled', true);
    await SyncEngine.instance.start();
    return registration;
  }

  Future<Uri> _baseUri() async {
    final prefs = await SharedPreferences.getInstance();
    final configured = prefs.getString('backend_api_url')?.trim();
    final raw = configured != null && configured.isNotEmpty
        ? configured
        : await ApiConfig.getBaseUrl();
    final uri = raw.isEmpty ? null : Uri.tryParse(raw);
    if (uri == null ||
        uri.scheme != 'https' ||
        !uri.hasAuthority ||
        uri.userInfo.isNotEmpty) {
      throw StateError(
        'Edge enrollment requires a configured HTTPS cloud API URL',
      );
    }
    return uri;
  }

  Map<String, String> _jsonHeaders(String token) => {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer $token',
  };

  Map<String, dynamic> _decodeSuccess(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      dynamic errorBody;
      if (response.body.isNotEmpty) {
        try {
          errorBody = jsonDecode(response.body);
        } on FormatException {
          errorBody = null;
        }
      }
      final code = errorBody is Map ? errorBody['code']?.toString() : null;
      throw StateError(
        'Edge enrollment failed (${code ?? response.statusCode})',
      );
    }
    if (response.body.isEmpty) {
      throw const FormatException(
        'Expected JSON object, received an empty response',
      );
    }
    final body = jsonDecode(response.body);
    if (body is! Map) {
      throw const FormatException('Expected JSON object');
    }
    return Map<String, dynamic>.from(body);
  }
}
