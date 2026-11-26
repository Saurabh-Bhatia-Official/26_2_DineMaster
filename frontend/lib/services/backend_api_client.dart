import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../core/api/api_config.dart';
import 'auth_token_storage.dart';

class BackendApiException implements Exception {
  final int statusCode;
  final String message;
  final String? code;
  final Map<String, dynamic> fieldErrors;

  const BackendApiException(
    this.statusCode,
    this.message, {
    this.code,
    this.fieldErrors = const {},
  });

  String get userMessage {
    if (fieldErrors.isEmpty) return message;
    final errors = fieldErrors.entries
        .map((entry) => '${_humanize(entry.key)}: ${entry.value}')
        .join('\n');
    return '$message\n$errors';
  }

  static String _humanize(String value) {
    final words = value
        .replaceAll(RegExp(r'([a-z])([A-Z])'), r'$1 $2')
        .replaceAll(RegExp(r'[._]+'), ' ')
        .trim();
    if (words.isEmpty) return 'Field';
    return '${words[0].toUpperCase()}${words.substring(1)}';
  }

  @override
  String toString() => userMessage;
}

class ConditionalJsonResponse {
  final dynamic data;
  final String? etag;
  final bool notModified;

  const ConditionalJsonResponse({
    required this.data,
    required this.etag,
    required this.notModified,
  });
}

class BackendApiClient {
  const BackendApiClient();

  static Future<bool>? _refreshInFlight;

  Future<Uri> _uri(String path) async {
    final prefs = await SharedPreferences.getInstance();
    final configuredBaseUrl = prefs.getString('backend_api_url')?.trim();
    final isServer = prefs.getBool('is_server') ?? true;
    final serverIp = prefs.getString('server_ip')?.trim();

    final fallbackHost = !isServer && serverIp != null && serverIp.isNotEmpty
        ? serverIp
        : 'localhost';

    String baseUrl;
    if (configuredBaseUrl != null && configuredBaseUrl.isNotEmpty) {
      baseUrl = configuredBaseUrl.replaceFirst(RegExp(r'/+$'), '');
    } else if (ApiConfig.envApiBaseUrl.isNotEmpty) {
      baseUrl = ApiConfig.envApiBaseUrl.replaceFirst(RegExp(r'/+$'), '');
    } else if (!isServer && serverIp != null && serverIp.isNotEmpty && !ApiConfig.isProduction) {
      baseUrl = 'http://$fallbackHost:8081';
    } else {
      baseUrl = ApiConfig.defaultBackendUrl;
    }

    // Prevent localhost URLs from being used in production
    if (ApiConfig.isProduction && ApiConfig.isLocalhost(baseUrl)) {
      baseUrl = ApiConfig.productionBackendUrl;
    }

    final normalizedPath =
        path.startsWith('/api/') &&
            !path.startsWith('/api/v1/') &&
            path != '/api/health'
        ? '/api/v1${path.substring(4)}'
        : path;

    return Uri.parse('$baseUrl$normalizedPath');
  }

  Future<dynamic> getJson(String path) async {
    return _requestJson('GET', path);
  }

  Future<ConditionalJsonResponse> getJsonConditional(
    String path, {
    String? etag,
  }) async {
    final token = await AuthTokenStorage.readAccessToken();
    final headers = etag == null ? null : {'If-None-Match': etag};
    var response = await _send('GET', path, null, token, extraHeaders: headers);
    if (response.statusCode == 401 &&
        !path.endsWith('/auth/login') &&
        !path.endsWith('/auth/refresh')) {
      final refreshToken = await AuthTokenStorage.readRefreshToken();
      if (refreshToken != null && await _refreshOnce(refreshToken)) {
        response = await _send(
          'GET',
          path,
          null,
          await AuthTokenStorage.readAccessToken(),
          extraHeaders: headers,
        );
      } else {
        await AuthTokenStorage.clear();
      }
    }
    if (response.statusCode == 304) {
      return ConditionalJsonResponse(
        data: null,
        etag: response.headers['etag'] ?? etag,
        notModified: true,
      );
    }
    return ConditionalJsonResponse(
      data: _decodeResponse(response),
      etag: response.headers['etag'],
      notModified: false,
    );
  }

  Future<dynamic> postJson(
    String path,
    Map<String, dynamic> body, {
    Map<String, String>? headers,
  }) async {
    return _requestJson('POST', path, body: body, extraHeaders: headers);
  }

  Future<dynamic> postMultipartJson(
    String path,
    Map<String, dynamic> fields, {
    required String fileField,
    required List<int> fileBytes,
    required String fileName,
  }) async {
    final token = await AuthTokenStorage.readAccessToken();
    final request = http.MultipartRequest('POST', await _uri(path))
      ..headers['Accept'] = 'application/json';
    if (token != null && token.isNotEmpty) {
      request.headers['Authorization'] = 'Bearer $token';
    }
    request.files.add(
      http.MultipartFile.fromString(
        'product',
        jsonEncode(fields),
        contentType: MediaType('application', 'json'),
      ),
    );
    request.files.add(
      http.MultipartFile.fromBytes(fileField, fileBytes, filename: fileName),
    );

    final client = http.Client();
    try {
      final streamed = await client
          .send(request)
          .timeout(const Duration(seconds: 30));
      return _decodeResponse(await http.Response.fromStream(streamed));
    } finally {
      client.close();
    }
  }

  Future<dynamic> putJson(String path, Map<String, dynamic> body) async {
    return _requestJson('PUT', path, body: body);
  }

  Future<dynamic> putJsonList(String path, List<dynamic> body) async {
    return _requestJson('PUT', path, body: body);
  }

  Future<dynamic> patchJson(String path, Map<String, dynamic> body) async {
    return _requestJson('PATCH', path, body: body);
  }

  Future<void> delete(String path) async {
    await _requestJson('DELETE', path);
  }

  Future<dynamic> _requestJson(
    String method,
    String path, {
    dynamic body,
    Map<String, String>? extraHeaders,
  }) async {
    final token = await AuthTokenStorage.readAccessToken();

    final response = await _send(
      method,
      path,
      body,
      token,
      extraHeaders: extraHeaders,
    );
    if (response.statusCode == 401 &&
        !path.endsWith('/auth/login') &&
        !path.endsWith('/auth/refresh')) {
      final refreshToken = await AuthTokenStorage.readRefreshToken();
      if (refreshToken != null && await _refreshOnce(refreshToken)) {
        return _decodeResponse(
          await _send(
            method,
            path,
            body,
            await AuthTokenStorage.readAccessToken(),
            extraHeaders: extraHeaders,
          ),
        );
      }
      await AuthTokenStorage.clear();
    }
    return _decodeResponse(response);
  }

  Future<http.Response> _send(
    String method,
    String path,
    dynamic body,
    String? token, {
    Map<String, String>? extraHeaders,
  }) async {
    final headers = <String, String>{
      'Accept': 'application/json',
      if (body != null) 'Content-Type': 'application/json',
    };

    if (token != null && token.isNotEmpty) {
      headers['Authorization'] = 'Bearer $token';
    }
    if (extraHeaders != null) headers.addAll(extraHeaders);

    final request = http.Request(method, await _uri(path))
      ..headers.addAll(headers);

    if (body != null) {
      request.body = jsonEncode(body);
    }

    final client = http.Client();
    try {
      final streamedResponse = await client
          .send(request)
          .timeout(const Duration(seconds: 30));
      return await http.Response.fromStream(streamedResponse);
    } finally {
      client.close();
    }
  }

  Future<bool> _refresh(String refreshToken) async {
    final response = await _send('POST', '/api/v1/auth/refresh', {
      'refreshToken': refreshToken,
    }, null);
    if (response.statusCode < 200 || response.statusCode >= 300) return false;
    final data = jsonDecode(response.body);
    if (data is! Map) return false;
    await AuthTokenStorage.save(
      accessToken: (data['accessToken'] ?? data['token'])?.toString(),
      refreshToken: data['refreshToken']?.toString(),
    );
    return true;
  }

  Future<bool> _refreshOnce(String refreshToken) {
    final activeRefresh = _refreshInFlight;
    if (activeRefresh != null) return activeRefresh;
    final pending = _refresh(refreshToken);
    _refreshInFlight = pending;
    return pending.whenComplete(() => _refreshInFlight = null);
  }

  dynamic _decodeResponse(http.Response response) {
    dynamic decoded;
    if (response.body.trim().isNotEmpty) {
      decoded = jsonDecode(response.body);
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
      final errorBody = decoded is Map
          ? Map<String, dynamic>.from(decoded)
          : <String, dynamic>{};
      final message =
          (errorBody['message'] ?? errorBody['error'] ?? errorBody['detail'])
              ?.toString()
              .trim();
      final details = errorBody['details'];
      final fieldErrors = <String, dynamic>{};
      if (details is Map) {
        details.forEach((key, value) {
          if (value != null && value.toString().trim().isNotEmpty) {
            fieldErrors[key.toString()] = value;
          }
        });
      } else if (errorBody['fieldErrors'] is Map) {
        (errorBody['fieldErrors'] as Map).forEach((key, value) {
          if (value != null && value.toString().trim().isNotEmpty) {
            fieldErrors[key.toString()] = value;
          }
        });
      }

      final fallback = response.body.trim().isNotEmpty
          ? response.body.trim()
          : 'Request failed (${response.statusCode}).';
      throw BackendApiException(
        response.statusCode,
        message == null || message.isEmpty ? fallback : message,
        code: errorBody['code']?.toString(),
        fieldErrors: fieldErrors,
      );
    }

    return decoded;
  }
}
