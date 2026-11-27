import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

/// Centralized API and environment configuration for DineMaster.
///
/// Connects the Vercel frontend with the Render backend, providing separate
/// development and production environment handling and preventing localhost
/// URLs from being inadvertently used in production.
class ApiConfig {
  /// Production Frontend URL (Hosted on Vercel)
  static const String productionFrontendUrl = 'https://26-2-dine-master.vercel.app';

  /// Local Development Backend URL
  static const String localBackendUrl = 'http://localhost:8081';

  /// Production Render Backend URL
  static const String renderBackendUrl = 'https://two6-2-dinemaster.onrender.com';

  /// Values loaded from the bundled frontend/.env file.
  static String get envApiBaseUrl => dotenv.env['API_BASE_URL']?.trim() ?? '';
  static String get envAppEnv => dotenv.env['APP_ENV']?.trim() ?? '';

  /// Checks if the application is running in production mode.
  /// Web builds, release builds, or explicit APP_ENV=production are treated as production.
  static bool get isProduction {
    final env = envAppEnv.trim().toLowerCase();
    if (env == 'production' || env == 'prod') {
      return true;
    }
    if (env == 'development' || env == 'dev' || env == 'local') {
      return false;
    }
    return kReleaseMode || kIsWeb;
  }

  /// Default backend URL depending on the current execution environment.
  static String get defaultBackendUrl {
    if (envApiBaseUrl.isNotEmpty) {
      return envApiBaseUrl.replaceFirst(RegExp(r'/+$'), '');
    }
    return isProduction ? renderBackendUrl : localBackendUrl;
  }

  /// Resolves the centralized API base URL, guaranteeing that localhost URLs
  /// are appropriately routed or mapped to the Render backend in production.
  static Future<String> getBaseUrl() async {
    final prefs = await SharedPreferences.getInstance();
    final configured = prefs.getString('backend_api_url')?.trim();

    String rawBaseUrl;
    if (configured != null && configured.isNotEmpty) {
      rawBaseUrl = configured;
    } else if (envApiBaseUrl.isNotEmpty) {
      rawBaseUrl = envApiBaseUrl;
    } else {
      final isServer = prefs.getBool('is_server') ?? true;
      final serverIp = prefs.getString('server_ip')?.trim();
      final fallbackHost = !isServer && serverIp != null && serverIp.isNotEmpty
          ? serverIp
          : (isProduction ? '' : 'localhost');

      if (fallbackHost.isNotEmpty && !isProduction) {
        rawBaseUrl = 'http://$fallbackHost:8081';
      } else {
        rawBaseUrl = isProduction ? renderBackendUrl : localBackendUrl;
      }
    }

    // Strip any trailing slashes
    var sanitized = rawBaseUrl.replaceFirst(RegExp(r'/+$'), '');

    if (sanitized.isEmpty) {
      sanitized = isProduction ? renderBackendUrl : localBackendUrl;
    }

    // If running in production and pointing to localhost, fallback to Render API
    if (isProduction && isLocalhost(sanitized)) {
      sanitized = renderBackendUrl;
    }

    return sanitized;
  }

  /// Checks whether a given URL points to localhost or a private loopback address.
  static bool isLocalhost(String url) {
    try {
      final uri = Uri.parse(url);
      final host = uri.host.toLowerCase();
      return host == 'localhost' ||
          host == '127.0.0.1' ||
          host == '0.0.0.0' ||
          host == '::1';
    } catch (_) {
      final lower = url.toLowerCase();
      return lower.contains('localhost') || lower.contains('127.0.0.1');
    }
  }

  /// Formats and normalizes a complete API URI for a given path.
  static Future<Uri> getApiUri(String path) async {
    final baseUrl = await getBaseUrl();
    final normalizedPath = path.startsWith('/api/') &&
            !path.startsWith('/api/v1/') &&
            path != '/api/health'
        ? '/api/v1${path.substring(4)}'
        : path;

    return Uri.parse('$baseUrl$normalizedPath');
  }
}
