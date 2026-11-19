import 'package:dine_master/core/database/database_helper.dart';
import 'package:dine_master/screens/dashboard_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late GoRouter router;

  setUp(() {
    SharedPreferences.setMockInitialValues({
      'username': 'Asha',
      'role': 'OWNER',
      'use_backend_api': false,
    });
    DatabaseHelper.currentRestaurantId = null;
  });

  tearDown(() {
    router.dispose();
    DatabaseHelper.currentRestaurantId = null;
  });

  Future<void> pumpDashboard(WidgetTester tester, Size size) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = size;
    router = GoRouter(
      initialLocation: '/dashboard',
      routes: [
        ShellRoute(
          builder: (context, state, child) => DashboardScreen(
            child: child,
            restaurantsLoader: () async => [
              {'id': 1, 'name': 'Central Kitchen'},
            ],
          ),
          routes: [
            GoRoute(
              path: '/dashboard',
              builder: (context, state) => Text(state.uri.path),
            ),
            GoRoute(
              path: '/dashboard/tables',
              builder: (context, state) => Text(state.uri.path),
            ),
            GoRoute(
              path: '/dashboard/analytics',
              builder: (context, state) => Text(state.uri.path),
            ),
          ],
        ),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(tester.takeException(), isNull);
  }

  testWidgets('dashboard shell renders and routes on desktop and tablet', (
    tester,
  ) async {
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await pumpDashboard(tester, const Size(1440, 900));
    expect(
      MediaQuery.sizeOf(tester.element(find.byType(DashboardScreen))).width,
      1440,
    );
    expect(find.text('Owner Account'), findsOneWidget);
    expect(find.text('Central Kitchen'), findsOneWidget);
    expect(find.text('/dashboard'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('Tables').first);
    await tester.pumpAndSettle();
    expect(find.text('/dashboard/tables'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await pumpDashboard(tester, const Size(768, 1024));
    expect(find.byIcon(Icons.menu), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('mobile dashboard opens drawer and navigates without overflow', (
    tester,
  ) async {
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await pumpDashboard(tester, const Size(390, 844));
    await tester.tap(find.byIcon(Icons.menu));
    await tester.pumpAndSettle();
    expect(find.text('Owner Account'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.text('Analytics'),
      240,
      scrollable: find
          .descendant(
            of: find.byType(Drawer),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.tap(find.text('Analytics'));
    await tester.pumpAndSettle();
    expect(find.text('/dashboard/analytics'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
