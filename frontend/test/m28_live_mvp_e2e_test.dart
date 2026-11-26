import 'dart:convert';
import 'dart:io';

import 'package:dine_master/core/database/database_helper.dart';
import 'package:dine_master/core/api/api_mode.dart';
import 'package:dine_master/repositories/user_repository.dart';
import 'package:dine_master/screens/waiter_order_screen.dart';
import 'package:dine_master/services/auth_token_storage.dart';
import 'package:dine_master/services/edge_host_credentials.dart';
import 'package:dine_master/services/edge_host_provisioning.dart';
import 'package:dine_master/services/sync_engine.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

const _api = 'https://localhost:8443';
const _offlineApi = 'https://localhost:8444';
const _branchId = 2;
const _localBranchId = 99;
const _branchSyncId = 'f1727f2f-3745-47eb-b240-4e292f10a6bf';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'M28 live activation, catalog pull, offline waiter order and automatic push',
    (tester) async {
      final certPath = Platform.environment['M28_E2E_CERT_PATH'];
      expect(
        certPath,
        isNotNull,
        reason: 'Set the temporary dev certificate path',
      );
      HttpOverrides.global = _TrustedCertificateOverrides(certPath!);

      // sqflite_common_ffi uses a path below the process working directory.
      // Move this test process into a unique temp directory before opening it.
      final testDirectory = (await tester.runAsync(
        () => Directory.systemTemp.createTemp('m28-e2e-'),
      ))!;
      Directory.current = testDirectory.path;
      DatabaseHelper.databaseName = 'm28_e2e.sqlite';
      DatabaseHelper.currentRestaurantId = _localBranchId;

      SharedPreferences.setMockInitialValues({});
      FlutterSecureStorage.setMockInitialValues({});
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(
        const MethodChannel('dev.fluttercommunity.plus/connectivity'),
        (call) async => call.method == 'check' ? ['wifi'] : null,
      );
      messenger.setMockStreamHandler(
        const EventChannel('dev.fluttercommunity.plus/connectivity_status'),
        MockStreamHandler.inline(
          onListen: (_, events) => events.success(['wifi']),
        ),
      );

      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('setup_data_choice', 'fresh');
      await prefs.setString('backend_api_url', _api);
      await prefs.setBool('use_backend_api', true);
      await prefs.setBool('is_server', true);
      await prefs.setInt('current_restaurant_id', _localBranchId);
      await AuthTokenStorage.clear();
      await EdgeHostCredentials.clear();

      final db = (await tester.runAsync(() async {
        final database = await DatabaseHelper.instance.database;
        final now = DateTime.now().toUtc().toIso8601String();
        await database.insert('restaurants', {
          'id': _localBranchId,
          'name': 'M28 E2E Branch',
          'created_at': now,
          'sync_id': _branchSyncId,
        });
        await database.update(
          'sync_identity_map',
          {
            'cloud_id': _branchId,
            'mapping_status': 'MATCHED',
            'updated_at': now,
          },
          where: 'entity_type=? AND edge_id=?',
          whereArgs: ['restaurants', _localBranchId],
        );
        return database;
      }))!;

      final user = await tester.runAsync(
        () => UserRepository().login(
          'DEFAULT-TENANT',
          'm28-e2e-owner',
          'M28-E2E-Development-Only!',
        ),
      );
      expect(
        user,
        isNotNull,
        reason: 'Synthetic cloud staff login must succeed',
      );

      final registration = (await tester.runAsync(
        () => EdgeHostProvisioning().activateBranch(_branchId),
      ))!;
      expect(registration['credentialIssued'], isTrue);
      expect(await EdgeHostCredentials.hostId(), isNotEmpty);
      expect(await EdgeHostCredentials.refreshToken(), isNotEmpty);

      await _waitFor(
        () async {
          final categories = await db.query(
            'categories',
            where: 'name=?',
            whereArgs: ['M28 E2E Category'],
          );
          final products = await db.query(
            'products',
            where: 'name=?',
            whereArgs: ['M28 E2E Product'],
          );
          return categories.isNotEmpty && products.isNotEmpty;
        },
        tester,
        'catalog pull from PostgreSQL',
      );
      expect(
        await _queryDb(
          tester,
          db,
          'categories',
          where: 'name=?',
          whereArgs: ['M28 E2E Category'],
        ),
        isNotEmpty,
      );
      expect(
        await _queryDb(
          tester,
          db,
          'products',
          where: 'name=?',
          whereArgs: ['M28 E2E Product'],
        ),
        isNotEmpty,
      );
      expect(
        await _queryDb(
          tester,
          db,
          'products',
          where: 'name IN (?,?)',
          whereArgs: ['M28 OTHER BRANCH ONLY', 'M28 OTHER TENANT ONLY'],
        ),
        isEmpty,
        reason: 'Catalog pull must exclude other branch and tenant records',
      );

      // Keep the user session and host authorization from the online login,
      // then make cloud transport unreachable while the local waiter workflow runs.
      print('M28 E2E: stopping cloud sync for offline order');
      await tester.runAsync(() => SyncEngine.instance.stop());
      print('M28 E2E: cloud sync stopped');
      print('M28 E2E: setting offline transport preferences');
      await prefs.setString('backend_api_url', _offlineApi);
      await prefs.setBool('use_backend_api', false);
      print('M28 E2E: offline transport preferences set');

      tester.view.physicalSize = const Size(1440, 1000);
      tester.view.devicePixelRatio = 1;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });
      print('M28 E2E: opening local waiter order screen');
      print('M28 E2E: pumping waiter route');
      await tester.pumpWidget(const MaterialApp(home: WaiterOrderScreen()));
      print('M28 E2E: waiter route mounted');
      await _settle(tester, 'initial waiter screen');
      print('M28 E2E: waiting for waiter screen initialization');
      final screenLoaded = await _waitForScreenLoad(tester);
      print('M28 E2E: waiter screen initialization complete=$screenLoaded');
      final loadingIndicators = find
          .byType(CircularProgressIndicator)
          .evaluate()
          .length;
      final visibleLabels = tester
          .widgetList<Text>(find.byType(Text))
          .map((widget) => widget.data ?? widget.textSpan?.toPlainText() ?? '')
          .where((label) => label.isNotEmpty)
          .take(30)
          .toList();
      final waiterPrefs = await SharedPreferences.getInstance();
      final waiterMode = await tester.runAsync(() => ApiMode.useBackendApi());
      final waiterTables = await tester.runAsync(
        () => db.query(
          'tables',
          where: 'restaurant_id=?',
          whereArgs: [_localBranchId],
        ),
      );
      final waiterUsers = await tester.runAsync(() => db.query('users'));
      print(
        'M28 E2E waiter diagnostic: loadingIndicators=$loadingIndicators, '
        'backendMode=$waiterMode, use_backend_api=${waiterPrefs.getBool('use_backend_api')}, '
        'restaurantId=${DatabaseHelper.currentRestaurantId}, '
        'db=${db.runtimeType}, tables=${waiterTables?.length}, '
        'users=${waiterUsers?.length}, labels=$visibleLabels',
      );
      expect(
        screenLoaded,
        isTrue,
        reason: 'WaiterOrderScreen remained in its loading state',
      );
      final waiterException = tester.takeException();
      if (waiterException != null) {
        print('M28 E2E waiter diagnostic exception: $waiterException');
      }
      await tester.tap(find.text('Start New Order'));
      await _settle(tester, 'new order screen');
      expect(find.text('M28 E2E Product'), findsWidgets);
      await tester.tap(find.text('M28 E2E Product').first);
      await _settle(tester, 'selected product');
      await tester.tap(find.text('Dine-In').first);
      await _settle(tester, 'dine-in selection');
      await tester.tap(find.text('Takeaway').last);
      await _settle(tester, 'takeaway selection');
      await tester.tap(find.text('Place Order'));
      final orderScreenLoaded = await _waitForScreenLoad(tester);
      expect(
        orderScreenLoaded,
        isTrue,
        reason: 'Order submission did not finish',
      );

      final localOrders = await _queryDb(tester, db, 'orders');
      final localItems = await _queryDb(tester, db, 'order_items');
      expect(localOrders, hasLength(1));
      expect(localItems, hasLength(1));
      expect(
        localOrders.single['sync_id'],
        matches(RegExp(r'^[0-9a-f-]{36}$')),
      );
      final pending = await _queryDb(
        tester,
        db,
        'sync_outbox',
        where:
            "entity_type IN ('orders','order_items') AND status IN ('PENDING','RETRY')",
      );
      expect(pending.map((row) => row['entity_type']).toSet(), {
        'orders',
        'order_items',
      });

      // Restore the trusted cloud URL and start the engine as on app/service
      // startup. No Sync button or direct syncOnce call is used for the push.
      await prefs.setString('backend_api_url', _api);
      await tester.runAsync(() => SyncEngine.instance.start());
      await _waitFor(
        () async {
          final remaining = await _queryDb(
            tester,
            db,
            'sync_outbox',
            where:
                "entity_type IN ('orders','order_items') AND status <> 'ACKED'",
          );
          return remaining.isEmpty;
        },
        tester,
        'automatic pending order push',
      );

      final orderOutbox = (await _queryDb(
        tester,
        db,
        'sync_outbox',
        where: "entity_type='orders' AND status='ACKED'",
        orderBy: 'created_at DESC',
        limit: 1,
      )).single;
      final hostToken = (await EdgeHostCredentials.accessToken())!;
      final duplicateClient = http.Client();
      try {
        final originalPayload = jsonDecode(
          orderOutbox['payload_json'] as String,
        );
        final change = {
          'changeId': orderOutbox['change_id'],
          'entityType': orderOutbox['entity_type'],
          'entitySyncId': orderOutbox['entity_sync_id'],
          'operation': orderOutbox['operation'],
          'payload': originalPayload,
        };
        final first = (await tester.runAsync(
          () => duplicateClient.post(
            Uri.parse('$_api/api/v1/sync/push'),
            headers: {
              'Authorization': 'Bearer $hostToken',
              'Content-Type': 'application/json',
            },
            body: jsonEncode({
              'changes': [change],
            }),
          ),
        ))!;
        final replay = (await tester.runAsync(
          () => duplicateClient.post(
            Uri.parse('$_api/api/v1/sync/push'),
            headers: {
              'Authorization': 'Bearer $hostToken',
              'Content-Type': 'application/json',
            },
            body: jsonEncode({
              'changes': [change],
            }),
          ),
        ))!;
        expect(first.statusCode, 200);
        expect(replay.statusCode, 200);
        expect(jsonDecode(replay.body), jsonDecode(first.body));

        final unauthorized = (await tester.runAsync(
          () => duplicateClient.get(Uri.parse('$_api/api/v1/sync/status')),
        ))!;
        expect(unauthorized.statusCode, 401);
      } finally {
        duplicateClient.close();
      }

      // Force an access-token refresh through normal engine transport, using
      // the already issued rotating refresh credential.
      final oldAccess = await EdgeHostCredentials.accessToken();
      await EdgeHostCredentials.updateTokens(
        accessToken: oldAccess!,
        expiresAt: DateTime.now().toUtc().subtract(const Duration(minutes: 1)),
        refreshToken: (await EdgeHostCredentials.refreshToken())!,
      );
      await tester.runAsync(() => SyncEngine.instance.syncOnce());
      expect(await EdgeHostCredentials.accessToken(), isNot(oldAccess));

      // A client that does not trust the disposable certificate must reject
      // this same HTTPS endpoint. Certificate verification is never bypassed.
      final untrustedClient = HttpClient(
        context: SecurityContext(withTrustedRoots: false),
      );
      try {
        await tester.runAsync(
          () => expectLater(
            untrustedClient.getUrl(Uri.parse('$_api/api/health')),
            throwsA(anything),
          ),
        );
      } finally {
        untrustedClient.close(force: true);
      }

      await tester.runAsync(() => SyncEngine.instance.stop());
      await AuthTokenStorage.clear();
      await EdgeHostCredentials.clear();
      await tester.runAsync(() => db.close());
      Directory.current = Directory.systemTemp.path;
      await testDirectory.delete(recursive: true);
    },
    timeout: const Timeout(Duration(minutes: 8)),
    skip: Platform.environment['M28_E2E_CERT_PATH'] == null,
  );
}

Future<void> _waitFor(
  Future<bool> Function() condition,
  WidgetTester tester,
  String description,
) async {
  final deadline = DateTime.now().add(const Duration(minutes: 2));
  while (DateTime.now().isBefore(deadline)) {
    // Conditions may already use tester.runAsync for SQLite/network access;
    // do not wrap them in another runAsync zone.
    if (await condition()) return;
    await tester.pump(const Duration(milliseconds: 250));
  }
  fail('Timed out waiting for $description');
}

Future<void> _settle(WidgetTester tester, String stage) async {
  print('M28 E2E: settling $stage');
  await tester.runAsync(
    () => Future<void>.delayed(const Duration(milliseconds: 500)),
  );
  // Wait for short entrance/state animations without waiting indefinitely for
  // repeating progress animations or periodic application timers.
  for (var frame = 0; frame < 6; frame++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<bool> _waitForScreenLoad(WidgetTester tester) async {
  final spinner = find.byType(CircularProgressIndicator);
  for (var frame = 0; frame < 40; frame++) {
    if (spinner.evaluate().isEmpty) return true;
    await tester.pump(const Duration(milliseconds: 25));
    // sqflite_common_ffi completes real I/O futures outside the widget test's
    // fake async clock. Yield to that event loop, then render any resulting
    // setState instead of relying on a fixed delay alone.
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 25)),
    );
    await tester.pump();
  }
  return spinner.evaluate().isEmpty;
}

Future<List<Map<String, Object?>>> _queryDb(
  WidgetTester tester,
  dynamic db,
  String table, {
  String? where,
  List<Object?>? whereArgs,
  String? orderBy,
  int? limit,
}) async {
  final rows = await tester.runAsync(
    () => db.query(
      table,
      where: where,
      whereArgs: whereArgs,
      orderBy: orderBy,
      limit: limit,
    ),
  );
  return (rows as List).cast<Map<String, Object?>>();
}

class _TrustedCertificateOverrides extends HttpOverrides {
  _TrustedCertificateOverrides(this.certificatePath);

  final String certificatePath;

  @override
  HttpClient createHttpClient(SecurityContext? context) {
    // Keep explicitly supplied contexts intact (the test uses one with no
    // trusted roots to verify certificate rejection). Normal package:http
    // clients receive only this test's trusted development certificate.
    if (context != null) return super.createHttpClient(context);
    final trustedContext = SecurityContext(withTrustedRoots: true)
      ..setTrustedCertificates(certificatePath);
    return super.createHttpClient(trustedContext);
  }
}
