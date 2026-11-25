import 'package:flutter_test/flutter_test.dart';
import 'package:shelf/shelf.dart';

import 'package:dine_master/core/database/network_database.dart';
import 'package:dine_master/services/edge_security_gate.dart';

void main() {
  group('Edge security gate', () {
    test(
      'rejects every legacy network SQL route before its handler runs',
      () async {
        final paths = [
          '/api/db/rawQuery',
          '/api/db/rawInsert',
          '/api/db/rawUpdate',
          '/api/db/rawDelete',
          '/api/db/execute',
          '/api/db/query',
          '/api/db/insert',
          '/api/db/update',
          '/api/db/delete',
          '/api/db/batch',
        ];
        var innerCalled = false;
        final handler = edgeSecurityGate((_) {
          innerCalled = true;
          return Response.ok('unexpected');
        });

        for (final path in paths) {
          final response = await handler(
            Request('POST', Uri.parse('http://localhost$path')),
          );
          expect(response.statusCode, 426, reason: path);
          expect(
            await response.readAsString(),
            contains('LEGACY_SQL_DISABLED'),
          );
        }
        expect(innerCalled, isFalse);
      },
    );

    test(
      'requires a scoped capability for the legacy public order route',
      () async {
        final response = await edgeSecurityGate(
          (_) => Response.ok('unexpected'),
        )(Request('POST', Uri.parse('http://localhost/api/order')));

        expect(response.statusCode, 426);
        expect(
          await response.readAsString(),
          contains('GUEST_CAPABILITY_REQUIRED'),
        );
      },
    );

    test(
      'advertises security state without enabling synchronization',
      () async {
        final response = await edgeSecurityGate((_) => Response.notFound(null))(
          Request(
            'GET',
            Uri.parse('http://localhost/api/edge/v1/capabilities'),
          ),
        );
        final body = await response.readAsString();

        expect(response.statusCode, 200);
        expect(body, contains('"authenticationRequired":true'));
        expect(body, contains('"domainOperationsAvailable":false'));
        expect(body, contains('"automaticSynchronizationEnabled":false'));
      },
    );

    test('passes unrelated requests to the configured handler', () async {
      var called = false;
      final response = await edgeSecurityGate((_) {
        called = true;
        return Response.ok('menu');
      })(Request('GET', Uri.parse('http://localhost/menu')));

      expect(called, isTrue);
      expect(response.statusCode, 200);
    });

    test('NetworkDatabase no longer submits generic SQL over HTTP', () async {
      final db = NetworkDatabase(serverIp: '127.0.0.1');

      await expectLater(
        db.rawQuery('SELECT 1'),
        throwsA(
          isA<UnsupportedError>().having(
            (error) => error.message,
            'message',
            contains('CLIENT_UPGRADE_REQUIRED'),
          ),
        ),
      );
    });
  });
}
