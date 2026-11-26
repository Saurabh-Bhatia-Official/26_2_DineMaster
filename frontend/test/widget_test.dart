import 'package:dine_master/screens/login_screen.dart';
import 'package:dine_master/screens/branch_selection_screen.dart';
import 'package:dine_master/core/database/database_helper.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Future<void> pumpLogin(WidgetTester tester, Size size) async {
    await tester.binding.setSurfaceSize(size);
    await tester.pumpWidget(const MaterialApp(home: LoginScreen()));
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('Welcome back'), findsOneWidget);
    expect(find.text('Sign in'), findsOneWidget);
    expect(find.text('Register'), findsOneWidget);
  }

  testWidgets('login layout adapts across laptop, tablet, and mobile', (
    WidgetTester tester,
  ) async {
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await pumpLogin(tester, const Size(1366, 768));
    expect(find.text('A better way to\nrun your restaurant.'), findsOneWidget);
    expect(find.byType(Image), findsNWidgets(2));

    await pumpLogin(tester, const Size(1024, 768));
    expect(find.text('DineMaster'), findsOneWidget);
    expect(find.byType(Image), findsOneWidget);

    await pumpLogin(tester, const Size(390, 844));
    expect(find.text('DineMaster'), findsOneWidget);
    expect(find.byType(Image), findsOneWidget);
  });

  testWidgets('branch selection adapts and filters API branch data', (
    WidgetTester tester,
  ) async {
    final branches = [
      {
        'id': 1,
        'name': 'Spice Route Central',
        'code': 'CENTRAL',
        'address': '14 Market Street, Pune',
        'phone': '+91 20 4000 1234',
        'timezone': 'Asia/Kolkata',
      },
      {
        'id': 2,
        'name': 'Harbor Kitchen',
        'code': 'HARBOR',
        'address': '8 Marina Drive, Mumbai',
        'phone': '+91 22 4000 5678',
        'timezone': 'Asia/Kolkata',
      },
    ];

    Future<void> pumpBranches(Size size) async {
      await tester.binding.setSurfaceSize(size);
      await tester.pumpWidget(
        MaterialApp(
          home: BranchSelectionScreen(restaurantsLoader: () async => branches),
        ),
      );
      await tester.pump();
      await tester.pump();
      expect(tester.takeException(), isNull);
    }

    addTearDown(() => tester.binding.setSurfaceSize(null));
    addTearDown(() => DatabaseHelper.currentRestaurantId = null);
    DatabaseHelper.currentRestaurantId = 1;

    await pumpBranches(const Size(1366, 768));
    expect(find.text('Find the floor\nyou need to run.'), findsOneWidget);
    expect(find.text('Spice Route Central'), findsOneWidget);
    expect(find.text('Harbor Kitchen'), findsOneWidget);
    expect(find.text('Selected'), findsOneWidget);
    expect(find.text('Select branch'), findsOneWidget);
    expect(find.byType(Image), findsOneWidget);

    await pumpBranches(const Size(1024, 768));
    expect(find.text('DineMaster'), findsOneWidget);
    expect(find.byType(Image), findsOneWidget);

    await pumpBranches(const Size(390, 844));
    await tester.enterText(find.byType(TextField), 'harbor');
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('Spice Route Central'), findsNothing);
    expect(find.text('Harbor Kitchen'), findsOneWidget);
  });
}
