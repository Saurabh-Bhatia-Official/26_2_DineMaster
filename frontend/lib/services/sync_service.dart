import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:logger/logger.dart';

/// Local UI invalidation stream. Network notifications stay disabled until an
/// authenticated WSS session and Edge grant validator are available.
class SyncService {
  static final SyncService instance = SyncService._init();
  final Logger _logger = Logger();

  final StreamController<String> _syncController =
      StreamController<String>.broadcast();
  Stream<String> get syncEvents => _syncController.stream;

  final ValueNotifier<bool> connectionState = ValueNotifier<bool>(false);

  SyncService._init();

  Future<void> initConnection() async {
    await closeConnection();
    _logger.i(
      kIsWeb
          ? 'Network notifications are unavailable in Flutter Web.'
          : 'Network notifications are disabled until authenticated WSS is implemented.',
    );
  }

  void broadcastEvent(String eventType, [Map<String, dynamic>? data]) {
    _syncController.add(
      jsonEncode({
        if (data != null) ...data,
        'event': eventType,
        'timestamp': DateTime.now().toIso8601String(),
      }),
    );
  }

  Future<void> closeConnection() async {
    connectionState.value = false;
  }
}
