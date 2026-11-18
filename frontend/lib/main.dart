import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:dine_master/core/theme/app_colors.dart';
import 'package:go_router/go_router.dart';
import 'screens/splash_screen.dart';
import 'screens/login_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/branch_selection_screen.dart';
import 'screens/overview_screen.dart';
import 'screens/pos_screen.dart';
import 'screens/orders_history_screen.dart';
import 'screens/products_management_screen.dart';
import 'screens/accounts_screen.dart';
import 'screens/tables_screen.dart';
import 'screens/kitchen_screen.dart';
import 'screens/inventory_screen.dart';
import 'screens/analytics_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/user_management_screen.dart';
import 'screens/printer_settings_screen.dart';
import 'screens/restaurants_screen.dart';
import 'screens/menu_card_screen.dart';
import 'screens/live_tracking_screen.dart';
import 'screens/customers_screen.dart';
import 'screens/waiter_order_screen.dart';
import 'services/server_service.dart';
import 'services/sync_service.dart';
import 'services/auth_token_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

final themeNotifier = ValueNotifier<ThemeMode>(ThemeMode.light);

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Start the local server only on non-web platforms.
  // Browsers do not support Dart's InternetAddress.anyIPv4 server.
  if (!kIsWeb) {
    await ServerService.instance.startServer();
  }

  await SyncService.instance.initConnection();

  final prefs = await SharedPreferences.getInstance();

  var accessToken = await AuthTokenStorage.readAccessToken();
  if (accessToken == null || accessToken.isEmpty) {
    accessToken = prefs.getString('token');
    if (accessToken != null && accessToken.isNotEmpty) {
      await AuthTokenStorage.save(accessToken: accessToken);
      await prefs.remove('token');
    }
  }
  final hasToken = accessToken != null && accessToken.isNotEmpty;

  // Always enforce clean light theme.
  themeNotifier.value = ThemeMode.light;
  await prefs.setString('theme_mode', 'light');

  runApp(MyApp(hasToken: hasToken));
}

GoRouter _buildRouter(bool hasToken, [String? initialLocation]) {
  return GoRouter(
    initialLocation: initialLocation ?? '/splash',
    routes: [
      GoRoute(
        path: '/splash',
        builder: (context, state) => const SplashScreen(),
      ),
      GoRoute(path: '/', builder: (context, state) => const LoginScreen()),
      GoRoute(
        path: '/branch_selection',
        builder: (context, state) => const BranchSelectionScreen(),
      ),
      ShellRoute(
        builder: (context, state, child) {
          return DashboardScreen(child: child);
        },
        routes: [
          GoRoute(
            path: '/dashboard',
            builder: (context, state) => const OverviewScreen(),
          ),
          GoRoute(
            path: '/dashboard/pos',
            builder: (context, state) {
              final orderId = state.uri.queryParameters['orderId'];
              final tableId = state.uri.queryParameters['tableId'];

              return PosScreen(
                reopenOrderId: orderId != null ? int.tryParse(orderId) : null,
                selectedTableId: tableId != null ? int.tryParse(tableId) : null,
              );
            },
          ),
          GoRoute(
            path: '/dashboard/orders',
            builder: (context, state) => const OrdersHistoryScreen(),
          ),
          GoRoute(
            path: '/dashboard/products_manage',
            builder: (context, state) => const ProductsManagementScreen(),
          ),
          GoRoute(
            path: '/dashboard/accounts',
            builder: (context, state) => const AccountsScreen(),
          ),
          GoRoute(
            path: '/dashboard/customers',
            builder: (context, state) => const CustomersScreen(),
          ),
          GoRoute(
            path: '/dashboard/tables',
            builder: (context, state) => const TablesScreen(),
          ),
          GoRoute(
            path: '/dashboard/waiter_order',
            builder: (context, state) {
              final tableId = state.uri.queryParameters['tableId'];

              return WaiterOrderScreen(
                selectedTableId: tableId != null ? int.tryParse(tableId) : null,
              );
            },
          ),
          GoRoute(
            path: '/dashboard/kitchen',
            builder: (context, state) => const KitchenScreen(),
          ),
          GoRoute(
            path: '/dashboard/live_tracking',
            builder: (context, state) => const LiveTrackingScreen(),
          ),
          GoRoute(
            path: '/dashboard/inventory',
            builder: (context, state) => const InventoryScreen(),
          ),
          GoRoute(
            path: '/dashboard/analytics',
            builder: (context, state) => const AnalyticsScreen(),
          ),
          GoRoute(
            path: '/dashboard/settings',
            builder: (context, state) => const SettingsScreen(),
          ),
          GoRoute(
            path: '/dashboard/user_management',
            builder: (context, state) => const UserManagementScreen(),
          ),
          GoRoute(
            path: '/dashboard/menu_card',
            builder: (context, state) => const MenuCardScreen(),
          ),
          GoRoute(
            path: '/dashboard/printer_settings',
            builder: (context, state) => const PrinterSettingsScreen(),
          ),
          GoRoute(
            path: '/dashboard/restaurants',
            builder: (context, state) => const RestaurantsScreen(),
          ),
        ],
      ),
    ],
  );
}

class MyApp extends StatefulWidget {
  final bool hasToken;
  final String? initialLocation;

  const MyApp({super.key, required this.hasToken, this.initialLocation});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();
    _router = _buildRouter(widget.hasToken, widget.initialLocation);
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: themeNotifier,
      builder: (context, currentThemeMode, child) {
        return MaterialApp.router(
          title: 'Dine Master',
          debugShowCheckedModeBanner: false,
          themeMode: ThemeMode.light,
          theme: ThemeData(
            useMaterial3: true,
            brightness: Brightness.light,
            primarySwatch: AppColors.primaryMaterialColor,
            colorScheme: ColorScheme.fromSeed(
              seedColor: AppColors.primary,
              primary: AppColors.primary,
              secondary: AppColors.secondary,
              brightness: Brightness.light,
              surface: Colors.white,
            ),
            scaffoldBackgroundColor: AppColors.background,
            cardColor: Colors.white,
            textTheme: const TextTheme(
              headlineMedium: TextStyle(
                color: AppColors.ink,
                fontSize: 26,
                height: 1.15,
                fontWeight: FontWeight.w700,
                letterSpacing: -.7,
              ),
              headlineSmall: TextStyle(
                color: AppColors.ink,
                fontSize: 21,
                height: 1.2,
                fontWeight: FontWeight.w700,
                letterSpacing: -.4,
              ),
              titleLarge: TextStyle(
                color: AppColors.ink,
                fontSize: 17,
                fontWeight: FontWeight.w700,
                letterSpacing: -.2,
              ),
              titleMedium: TextStyle(
                color: AppColors.ink,
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
              bodyLarge: TextStyle(color: AppColors.ink, fontSize: 14),
              bodyMedium: TextStyle(
                color: AppColors.muted,
                fontSize: 13,
                height: 1.45,
              ),
              labelLarge: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                letterSpacing: .1,
              ),
            ),
            appBarTheme: const AppBarTheme(
              elevation: 0,
              scrolledUnderElevation: 0,
              toolbarHeight: 72,
              backgroundColor: Colors.white,
              surfaceTintColor: Colors.transparent,
              foregroundColor: AppColors.ink,
              iconTheme: IconThemeData(color: AppColors.ink),
              titleTextStyle: TextStyle(
                color: AppColors.ink,
                fontSize: 17,
                fontWeight: FontWeight.w700,
                letterSpacing: -.25,
              ),
            ),
            cardTheme: const CardThemeData(
              elevation: 0,
              color: Colors.white,
              surfaceTintColor: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.all(Radius.circular(16)),
                side: BorderSide(color: AppColors.line),
              ),
            ),
            dividerColor: AppColors.line,
            dataTableTheme: const DataTableThemeData(
              headingRowColor: WidgetStatePropertyAll(Color(0xFFFFF6F1)),
              headingTextStyle: TextStyle(
                color: AppColors.ink,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
              dataTextStyle: TextStyle(color: AppColors.ink, fontSize: 13),
              dividerThickness: 1,
              horizontalMargin: 18,
              columnSpacing: 24,
            ),
            popupMenuTheme: PopupMenuThemeData(
              color: Colors.white,
              surfaceTintColor: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
                side: const BorderSide(color: AppColors.line),
              ),
              textStyle: const TextStyle(color: AppColors.ink, fontSize: 13),
            ),
            tooltipTheme: TooltipThemeData(
              decoration: BoxDecoration(
                color: AppColors.charcoalDeep,
                borderRadius: BorderRadius.circular(8),
              ),
              textStyle: const TextStyle(color: Colors.white, fontSize: 11),
            ),
            snackBarTheme: SnackBarThemeData(
              behavior: SnackBarBehavior.floating,
              backgroundColor: AppColors.charcoalDeep,
              contentTextStyle: const TextStyle(color: Colors.white),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            chipTheme: ChipThemeData(
              backgroundColor: const Color(0xFFF2F3F3),
              selectedColor: AppColors.orangeLight,
              side: BorderSide.none,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(20),
              ),
              labelStyle: const TextStyle(color: AppColors.ink, fontSize: 12),
            ),
            inputDecorationTheme: InputDecorationTheme(
              filled: true,
              fillColor: Colors.white,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 14,
                vertical: 15,
              ),
              hintStyle: const TextStyle(color: AppColors.muted, fontSize: 13),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: const BorderSide(color: AppColors.line),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: const BorderSide(color: AppColors.line),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: const BorderSide(
                  color: AppColors.orange,
                  width: 1.4,
                ),
              ),
            ),
            filledButtonTheme: FilledButtonThemeData(
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.orange,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
            elevatedButtonTheme: ElevatedButtonThemeData(
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.orange,
                foregroundColor: Colors.white,
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
            outlinedButtonTheme: OutlinedButtonThemeData(
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.ink,
                side: const BorderSide(color: AppColors.line),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
            textButtonTheme: TextButtonThemeData(
              style: TextButton.styleFrom(
                foregroundColor: AppColors.orange,
                textStyle: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
            dialogTheme: DialogThemeData(
              backgroundColor: Colors.white,
              surfaceTintColor: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(20),
                side: const BorderSide(color: AppColors.line),
              ),
            ),
            tabBarTheme: const TabBarThemeData(
              labelColor: AppColors.orange,
              unselectedLabelColor: AppColors.muted,
              indicatorColor: AppColors.orange,
              dividerColor: AppColors.line,
            ),
          ),
          routerConfig: _router,
        );
      },
    );
  }
}
