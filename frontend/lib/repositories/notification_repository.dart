import '../models/notification_model.dart';
import '../services/backend_api_client.dart';

class NotificationRepository {
  final BackendApiClient _apiClient = const BackendApiClient();

  Future<List<NotificationModel>> getNotifications({
    bool unreadOnly = false,
    int limit = 50,
  }) async {
    final response = await _apiClient.getJson(
      '/api/v1/notifications?unread=$unreadOnly&limit=$limit',
    );
    if (response is! List) return const [];
    return response
        .whereType<Map>()
        .map((item) => NotificationModel.fromJson(
              Map<String, dynamic>.from(item),
            ))
        .toList();
  }

  Future<void> markRead(int id) async {
    await _apiClient.postJson('/api/v1/notifications/$id/read', const {});
  }

  Future<int> markAllRead() async {
    final response = await _apiClient.postJson(
      '/api/v1/notifications/read-all',
      const {},
    );
    return response is Map ? (response['updatedCount'] as num?)?.toInt() ?? 0 : 0;
  }
}
