import 'dart:convert';

import 'package:shelf/shelf.dart';

/// Fails closed for legacy database proxy and guest-order routes until their
/// authenticated, typed Edge APIs are implemented.
Handler edgeSecurityGate(Handler inner) {
  return (Request request) async {
    final rawPath = request.url.path;
    final path = rawPath.startsWith('/') ? rawPath : '/$rawPath';
    if (request.method == 'GET' && path == '/api/edge/v1/capabilities') {
      return Response.ok(
        jsonEncode({
          'protocol': 'dinemaster-edge/1',
          'minimumClientVersion': '1.0.0',
          'authenticationRequired': true,
          'domainOperationsAvailable': false,
          'automaticSynchronizationEnabled': false,
        }),
        headers: {'content-type': 'application/json'},
      );
    }

    if (path == '/api/db' || path.startsWith('/api/db/')) {
      return _upgradeRequired('LEGACY_SQL_DISABLED');
    }
    if (request.method == 'POST' && path == '/api/order') {
      return _upgradeRequired('GUEST_CAPABILITY_REQUIRED');
    }
    return inner(request);
  };
}

Response _upgradeRequired(String code) => Response(
  426,
  body: jsonEncode({
    'error': {
      'code': code,
      'message': 'This client protocol is no longer supported. Update the app.',
      'retryable': false,
    },
  }),
  headers: {'content-type': 'application/json'},
);
