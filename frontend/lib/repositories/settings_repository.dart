import '../services/backend_api_client.dart';

class SettingsRepository {
  final BackendApiClient _apiClient = const BackendApiClient();

  Future<Map<String, dynamic>> getSettings({int? branchId}) async {
    final query = branchId == null ? '' : '?branchId=$branchId';
    final response = await _apiClient.getJson('/api/v1/settings$query');
    if (response is! Map || response['settings'] is! Map) {
      throw const FormatException('Invalid settings response');
    }
    final values = <String, dynamic>{};
    (response['settings'] as Map).forEach((key, setting) {
      if (setting is Map) values[key.toString()] = setting['value'];
    });
    return values;
  }

  Future<void> updateSetting(
    String key,
    Object value, {
    int? branchId,
  }) async {
    final query = branchId == null ? '' : '?branchId=$branchId';
    await _apiClient.putJson(
      '/api/v1/settings/$key$query',
      {'value': value},
    );
  }
}
