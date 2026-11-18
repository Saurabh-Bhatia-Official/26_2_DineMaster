import 'package:flutter/material.dart';

/// Shared DineMaster palette, sampled from the login and branch selection UI.
class AppColors {
  static const Color ink = Color(0xFF202326);
  static const Color muted = Color(0xFF71777D);
  static const Color line = Color(0xFFE7E8EA);
  static const Color background = Color(0xFFF6F6F4);
  static const Color surface = Color(0xFFFFFFFF);
  static const Color orange = Color(0xFFE56A2F);
  static const Color orangeLight = Color(0xFFFFEEE5);
  static const Color charcoal = Color(0xFF202326);
  static const Color charcoalDeep = Color(0xFF191B1D);

  // Keep the legacy names used by existing feature screens while aligning
  // their output with the public brand palette.
  static const Color navyBlue = charcoal;
  static const Color gold = orange;
  static const Color white = surface;
  static const Color navyBlue50 = Color(0xFFFFEEE5);
  static const Color navyBlue100 = Color(0xFFFFD4BE);
  static const Color primary = orange;
  static const Color secondary = charcoal;
  static const Color accent = orange;
  static const Color primaryLight = orangeLight;

  static MaterialColor get primaryMaterialColor =>
      MaterialColor(orange.value, const <int, Color>{
        50: Color(0xFFFFF6F1),
        100: Color(0xFFFFEEE5),
        200: Color(0xFFFFD4BE),
        300: Color(0xFFFFB58E),
        400: Color(0xFFF58C57),
        500: orange,
        600: Color(0xFFD45C28),
        700: Color(0xFFB84B20),
        800: Color(0xFF963C1A),
        900: Color(0xFF743016),
      });
}
