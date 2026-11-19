import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/database/database_helper.dart';
import '../repositories/user_repository.dart';
import '../services/auth_token_storage.dart';
import '../services/backend_api_client.dart';

const _orange = Color(0xFFE56A2F);
const _ink = Color(0xFF202326);
const _muted = Color(0xFF71777D);
const _line = Color(0xFFE7E8EA);
const _roles = [
  'Owner',
  'Manager',
  'Cashier',
  'Waiter',
  'Chef',
  'Inventory Manager',
  'Other Staff',
];

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _tenantCodeController = TextEditingController();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _obscurePassword = true;
  bool _rememberMe = false;
  bool _isLoading = false;
  String _selectedRole = 'Owner';
  String? _loginError;

  @override
  void dispose() {
    _tenantCodeController.dispose();
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<bool> _changeInitialPassword(String currentPassword) async {
    final controller = TextEditingController();
    final formKey = GlobalKey<FormState>();
    final newPassword = await showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Change your temporary password'),
        content: Form(
          key: formKey,
          child: TextFormField(
            controller: controller,
            obscureText: true,
            autofocus: true,
            decoration: const InputDecoration(labelText: 'New password'),
            validator: (value) => value == null || value.length < 10
                ? 'Use at least 10 characters'
                : null,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              if (formKey.currentState!.validate()) {
                Navigator.pop(dialogContext, controller.text);
              }
            },
            child: const Text('Change password'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (newPassword == null || !mounted) return false;
    try {
      await UserRepository().changePassword(currentPassword, newPassword);
      await AuthTokenStorage.clear();
      if (!mounted) return false;
      _passwordController.text = newPassword;
      _showMessage('Password changed. Sign in again with your new password.');
      return false;
    } on BackendApiException catch (error) {
      if (mounted) _showMessage('Password change failed: ${error.message}');
      return false;
    }
  }

  Future<void> _login() async {
    if (_isLoading) return;
    FocusScope.of(context).unfocus();
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _isLoading = true;
      _loginError = null;
    });
    try {
      final username = _usernameController.text.trim();
      final tenantCode = _tenantCodeController.text.trim();
      final password = _passwordController.text;
      final prefs = await SharedPreferences.getInstance();
      final useBackendApi =
          kIsWeb || (prefs.getBool('use_backend_api') ?? true);

      if (useBackendApi) {
        if (tenantCode.isEmpty) {
          setState(() => _loginError = 'Enter your tenant code to sign in.');
          return;
        }
        final user = await UserRepository().login(
          tenantCode,
          username,
          password,
        );
        if (!mounted) return;
        if (user == null ||
            _normalizeRole(user.role) != _normalizeRole(_selectedRole)) {
          setState(
            () => _loginError =
                'Invalid email or password. Check your details and try again.',
          );
          return;
        }
        if (user.mustChangePassword) {
          await _changeInitialPassword(password);
          return;
        }
        await prefs.setBool('remember_me', _rememberMe);
        if (mounted) context.go('/branch_selection');
        return;
      }

      final db = await DatabaseHelper.instance.database;
      final users = await db.query(
        'users',
        where: 'username = ? AND password = ? AND role = ? AND is_active = 1',
        whereArgs: [
          username,
          DatabaseHelper.hashPassword(password),
          _selectedRole,
        ],
      );
      if (!mounted) return;
      if (users.isEmpty) {
        setState(
          () => _loginError =
              'Invalid email or password. Check your details and try again.',
        );
        return;
      }
      final sessionToken = base64Url.encode(
        utf8.encode('$username:${DateTime.now().millisecondsSinceEpoch}'),
      );
      await prefs.setString('token', sessionToken);
      await prefs.setString('username', username);
      await prefs.setString('role', _selectedRole);
      await prefs.setBool('remember_me', _rememberMe);
      if (mounted) context.go('/branch_selection');
    } on BackendApiException catch (error) {
      if (!mounted) return;
      setState(
        () => _loginError = error.statusCode >= 500
            ? 'The service is having trouble right now. Please try again shortly.'
            : 'Invalid email or password. Check your details and try again.',
      );
    } catch (_) {
      if (!mounted) return;
      setState(
        () => _loginError =
            'Unable to connect. Check your internet connection and try again.',
      );
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF6F6F4),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            // A split card needs enough room for a usable 440px form and the
            // brand panel. Tablets use the single-column version instead.
            final wide = constraints.maxWidth >= 1080;
            final compactHeight = constraints.maxHeight < 760;
            return Center(
              child: SingleChildScrollView(
                padding: EdgeInsets.symmetric(
                  horizontal: constraints.maxWidth < 500 ? 20 : 36,
                  vertical: compactHeight ? 16 : 28,
                ),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1120),
                  child: Container(
                    clipBehavior: Clip.antiAlias,
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(24),
                      border: Border.all(color: _line),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x120D1117),
                          blurRadius: 40,
                          offset: Offset(0, 18),
                        ),
                      ],
                    ),
                    child: wide
                        ? Row(
                            children: [
                              Expanded(
                                child: _brandPanel(compact: compactHeight),
                              ),
                              Expanded(
                                child: _loginPanel(
                                  showBrandHeader: false,
                                  compact: compactHeight,
                                ),
                              ),
                            ],
                          )
                        : _loginPanel(
                            showBrandHeader: true,
                            compact: constraints.maxHeight < 700,
                          ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _brandPanel({required bool compact}) {
    final panelPadding = compact ? 36.0 : 44.0;
    final logoSize = compact ? 112.0 : 128.0;

    return Container(
      // The parent is scrollable, so the brand panel needs a finite height for
      // its Spacer widgets to distribute content correctly on desktop.
      constraints: BoxConstraints.tightFor(height: compact ? 610 : 650),
      padding: EdgeInsets.all(panelPadding),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFF292B2D), Color(0xFF191B1D)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.asset(
                  'assets/images/logo.jpg',
                  width: 46,
                  height: 46,
                  fit: BoxFit.cover,
                ),
              ),
              const SizedBox(width: 12),
              const Text(
                'DineMaster',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 21,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -.4,
                ),
              ),
            ],
          ),
          const Spacer(),
          Container(
            width: logoSize,
            height: logoSize,
            padding: const EdgeInsets.all(4),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: .08),
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: Colors.white.withValues(alpha: .14)),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: Image.asset('assets/images/logo.jpg', fit: BoxFit.cover),
            ),
          ),
          SizedBox(height: compact ? 22 : 26),
          Text(
            'A better way to\nrun your restaurant.',
            style: TextStyle(
              color: Colors.white,
              fontSize: compact ? 34 : 36,
              height: 1.16,
              fontWeight: FontWeight.w700,
              letterSpacing: -1.2,
            ),
          ),
          SizedBox(height: compact ? 14 : 16),
          const Text(
            'Bring your team, service, and operations together in one calm, connected workspace.',
            style: TextStyle(
              color: Color(0xFFB9BABC),
              fontSize: 15,
              height: 1.65,
            ),
          ),
          SizedBox(height: compact ? 24 : 30),
          _feature(Icons.bolt_rounded, 'Service that keeps moving'),
          const SizedBox(height: 15),
          _feature(Icons.insights_rounded, 'A clearer view of your business'),
          const Spacer(),
          const Text(
            'BUILT FOR HOSPITALITY',
            style: TextStyle(
              color: Color(0xFF9A9C9E),
              fontSize: 10,
              fontWeight: FontWeight.w700,
              letterSpacing: 1.5,
            ),
          ),
        ],
      ),
    );
  }

  Widget _feature(IconData icon, String label) => Row(
    children: [
      Icon(icon, size: 17, color: const Color(0xFFFFA476)),
      const SizedBox(width: 11),
      Text(
        label,
        style: const TextStyle(
          color: Color(0xFFE2E2E2),
          fontSize: 13,
          fontWeight: FontWeight.w500,
        ),
      ),
    ],
  );

  Widget _loginPanel({
    required bool showBrandHeader,
    required bool compact,
  }) => Padding(
    padding: EdgeInsets.symmetric(
      horizontal: MediaQuery.sizeOf(context).width < 520 ? 24 : 56,
      vertical: compact ? 28 : 44,
    ),
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 440),
      child: Form(
        key: _formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            if (showBrandHeader) ...[
              Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.asset(
                      'assets/images/logo.jpg',
                      width: 38,
                      height: 38,
                      fit: BoxFit.cover,
                    ),
                  ),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text(
                      'DineMaster',
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: _ink,
                        fontSize: 19,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
              SizedBox(height: compact ? 26 : 38),
            ],
            const Text(
              'Welcome back',
              style: TextStyle(
                color: _ink,
                fontSize: 30,
                fontWeight: FontWeight.w700,
                letterSpacing: -.8,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Sign in to continue to your workspace.',
              style: TextStyle(color: _muted, fontSize: 14),
            ),
            const SizedBox(height: 30),
            _label('Tenant code'),
            TextFormField(
              controller: _tenantCodeController,
              textCapitalization: TextCapitalization.characters,
              textInputAction: TextInputAction.next,
              validator: (_) => null,
              decoration: _decoration(
                'e.g. SPICEROUTE',
                Icons.apartment_rounded,
              ),
            ),
            const SizedBox(height: 18),
            _label('Email or username'),
            TextFormField(
              controller: _usernameController,
              keyboardType: TextInputType.emailAddress,
              textInputAction: TextInputAction.next,
              autofillHints: const [
                AutofillHints.username,
                AutofillHints.email,
              ],
              validator: (value) {
                final input = value?.trim() ?? '';
                if (input.isEmpty) return 'Enter your email or username.';
                if (input.contains('@') &&
                    !RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(input)) {
                  return 'Enter a valid email address.';
                }
                return null;
              },
              decoration: _decoration(
                'you@restaurant.com',
                Icons.person_outline_rounded,
              ),
            ),
            const SizedBox(height: 18),
            _label('Password'),
            TextFormField(
              controller: _passwordController,
              obscureText: _obscurePassword,
              textInputAction: TextInputAction.done,
              onFieldSubmitted: (_) => _login(),
              autofillHints: const [AutofillHints.password],
              validator: (value) => (value == null || value.isEmpty)
                  ? 'Enter your password.'
                  : null,
              decoration:
                  _decoration(
                    'Enter your password',
                    Icons.lock_outline_rounded,
                  ).copyWith(
                    suffixIcon: IconButton(
                      tooltip: _obscurePassword
                          ? 'Show password'
                          : 'Hide password',
                      onPressed: () =>
                          setState(() => _obscurePassword = !_obscurePassword),
                      icon: Icon(
                        _obscurePassword
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                        color: _muted,
                        size: 20,
                      ),
                    ),
                  ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: _selectedRole,
                    isExpanded: true,
                    icon: const Icon(Icons.expand_more_rounded),
                    items: _roles
                        .map(
                          (role) => DropdownMenuItem(
                            value: role,
                            child: Text(role, overflow: TextOverflow.ellipsis),
                          ),
                        )
                        .toList(),
                    onChanged: _isLoading
                        ? null
                        : (value) {
                            if (value != null) {
                              setState(() => _selectedRole = value);
                            }
                          },
                    decoration: _decoration('Role', Icons.badge_outlined),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                GestureDetector(
                  onTap: () => setState(() => _rememberMe = !_rememberMe),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        width: 24,
                        height: 24,
                        child: Checkbox(
                          value: _rememberMe,
                          activeColor: _orange,
                          onChanged: (value) =>
                              setState(() => _rememberMe = value ?? false),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(5),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      const Text(
                        'Remember me',
                        style: TextStyle(color: _muted, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                TextButton(
                  onPressed: () => _showMessage(
                    'Please contact your restaurant administrator to reset your password.',
                  ),
                  style: TextButton.styleFrom(
                    foregroundColor: _orange,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
                  child: const Text(
                    'Forgot password?',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            ),
            if (_loginError != null) ...[
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 13,
                  vertical: 11,
                ),
                decoration: BoxDecoration(
                  color: const Color(0xFFFFF1EF),
                  border: Border.all(color: const Color(0xFFF8D0CB)),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(
                      Icons.error_outline_rounded,
                      color: Color(0xFFB84032),
                      size: 18,
                    ),
                    const SizedBox(width: 9),
                    Expanded(
                      child: Text(
                        _loginError!,
                        style: const TextStyle(
                          color: Color(0xFF9D352A),
                          fontSize: 12,
                          height: 1.4,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 18),
            SizedBox(
              height: 52,
              child: FilledButton(
                onPressed: _isLoading ? null : _login,
                style: FilledButton.styleFrom(
                  backgroundColor: _orange,
                  disabledBackgroundColor: _orange.withValues(alpha: .7),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(11),
                  ),
                ),
                child: _isLoading
                    ? const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          ),
                          SizedBox(width: 11),
                          Text(
                            'Logging in...',
                            style: TextStyle(fontWeight: FontWeight.w600),
                          ),
                        ],
                      )
                    : const Text(
                        'Sign in',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
              ),
            ),
            const SizedBox(height: 20),
            Wrap(
              alignment: WrapAlignment.center,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                const Text(
                  "New to DineMaster? ",
                  style: TextStyle(color: _muted, fontSize: 13),
                ),
                TextButton(
                  onPressed: _isLoading ? null : _showRegisterDialog,
                  style: TextButton.styleFrom(
                    foregroundColor: _orange,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
                  child: const Text(
                    'Register',
                    style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'Secure access for your restaurant team',
              textAlign: TextAlign.center,
              style: TextStyle(color: Color(0xFF9A9EA2), fontSize: 11),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _label(String label) => Padding(
    padding: const EdgeInsets.only(bottom: 7),
    child: Text(
      label,
      style: const TextStyle(
        color: Color(0xFF35393D),
        fontSize: 12,
        fontWeight: FontWeight.w600,
      ),
    ),
  );

  InputDecoration _decoration(String hint, IconData icon) => InputDecoration(
    hintText: hint,
    hintStyle: const TextStyle(color: Color(0xFFAAADB0), fontSize: 13),
    prefixIcon: Icon(icon, color: const Color(0xFF8A8F94), size: 19),
    filled: true,
    fillColor: const Color(0xFFFCFCFB),
    contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 15),
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(10),
      borderSide: const BorderSide(color: _line),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(10),
      borderSide: const BorderSide(color: _line),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(10),
      borderSide: const BorderSide(color: _orange, width: 1.4),
    ),
    errorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(10),
      borderSide: const BorderSide(color: Color(0xFFD65A4A)),
    ),
    focusedErrorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(10),
      borderSide: const BorderSide(color: Color(0xFFD65A4A), width: 1.4),
    ),
  );

  String _normalizeRole(String role) {
    final normalized = role
        .trim()
        .toLowerCase()
        .replaceAll(RegExp(r'[\s-]+'), '_')
        .replaceFirst(RegExp(r'^role_'), '');
    return switch (normalized) {
      'restaurant_admin' || 'admin' => 'owner',
      'cook' => 'chef',
      'employee' || 'other_staff' => 'staff',
      _ => normalized,
    };
  }

  void _showRegisterDialog() {
    final nameController = TextEditingController();
    final usernameController = TextEditingController();
    final passwordController = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String role = 'Waiter';
    bool obscure = true;
    bool registering = false;

    showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, updateDialog) {
          final password = passwordController.text;
          final strength = password.isEmpty
              ? 0
              : password.length < 6
              ? 1
              : password.length < 10
              ? 2
              : 3;
          final strengthColor = [
            const Color(0xFFE8E9EA),
            const Color(0xFFD65A4A),
            const Color(0xFFE5A23B),
            const Color(0xFF3C9A68),
          ][strength];
          return AlertDialog(
            backgroundColor: Colors.white,
            surfaceTintColor: Colors.transparent,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(20),
            ),
            title: const Text(
              'Register account',
              style: TextStyle(color: _ink, fontWeight: FontWeight.w700),
            ),
            content: SizedBox(
              width: 420,
              child: SingleChildScrollView(
                child: Form(
                  key: formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Text(
                        'Set up your DineMaster team profile.',
                        style: TextStyle(color: _muted, fontSize: 13),
                      ),
                      const SizedBox(height: 20),
                      TextFormField(
                        controller: nameController,
                        textCapitalization: TextCapitalization.words,
                        decoration: _decoration(
                          'Full name',
                          Icons.person_outline_rounded,
                        ),
                        validator: (value) =>
                            value == null || value.trim().isEmpty
                            ? 'Enter your full name.'
                            : null,
                      ),
                      const SizedBox(height: 13),
                      TextFormField(
                        controller: usernameController,
                        textInputAction: TextInputAction.next,
                        decoration: _decoration(
                          'Choose a username',
                          Icons.alternate_email_rounded,
                        ),
                        validator: (value) {
                          final username = value?.trim() ?? '';
                          if (username.isEmpty) return 'Choose a username.';
                          if (username.length < 3) {
                            return 'Use at least 3 characters.';
                          }
                          return null;
                        },
                      ),
                      const SizedBox(height: 13),
                      TextFormField(
                        controller: passwordController,
                        obscureText: obscure,
                        onChanged: (_) => updateDialog(() {}),
                        decoration:
                            _decoration(
                              'Create a password',
                              Icons.lock_outline_rounded,
                            ).copyWith(
                              suffixIcon: IconButton(
                                onPressed: () =>
                                    updateDialog(() => obscure = !obscure),
                                icon: Icon(
                                  obscure
                                      ? Icons.visibility_off_outlined
                                      : Icons.visibility_outlined,
                                  size: 19,
                                  color: _muted,
                                ),
                              ),
                            ),
                        validator: (value) => value == null || value.length < 8
                            ? 'Use at least 8 characters.'
                            : null,
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: List.generate(
                          3,
                          (index) => Expanded(
                            child: Container(
                              margin: EdgeInsets.only(
                                right: index == 2 ? 0 : 5,
                              ),
                              height: 4,
                              decoration: BoxDecoration(
                                color: strength > index
                                    ? strengthColor
                                    : const Color(0xFFE8E9EA),
                                borderRadius: BorderRadius.circular(4),
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        [
                          'Use 8 or more characters for a stronger password.',
                          'Password strength: weak',
                          'Password strength: fair',
                          'Password strength: strong',
                        ][strength],
                        style: const TextStyle(color: _muted, fontSize: 11),
                      ),
                      const SizedBox(height: 14),
                      DropdownButtonFormField<String>(
                        initialValue: role,
                        isExpanded: true,
                        items: ['Owner', 'Manager', 'Cashier', 'Waiter', 'Chef']
                            .map(
                              (value) => DropdownMenuItem(
                                value: value,
                                child: Text(value),
                              ),
                            )
                            .toList(),
                        onChanged: registering
                            ? null
                            : (value) {
                                if (value != null) {
                                  updateDialog(() => role = value);
                                }
                              },
                        decoration: _decoration('Role', Icons.badge_outlined),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 20),
            actions: [
              TextButton(
                onPressed: registering
                    ? null
                    : () => Navigator.pop(dialogContext),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: registering
                    ? null
                    : () async {
                        if (!formKey.currentState!.validate()) return;
                        updateDialog(() => registering = true);
                        try {
                          final db = await DatabaseHelper.instance.database;
                          final username = usernameController.text.trim();
                          final existing = await db.query(
                            'users',
                            columns: ['username'],
                            where: 'LOWER(username) = ?',
                            whereArgs: [username.toLowerCase()],
                            limit: 1,
                          );
                          if (existing.isNotEmpty) {
                            updateDialog(() => registering = false);
                            formKey.currentState!.validate();
                            _showMessage(
                              'That username is already in use. Choose another one.',
                            );
                            return;
                          }
                          await db.insert('users', {
                            'name': nameController.text.trim(),
                            'username': username,
                            'password': DatabaseHelper.hashPassword(
                              passwordController.text,
                            ),
                            'role': role,
                            'is_active': 1,
                            'created_at': DateTime.now().toIso8601String(),
                          });
                          if (!mounted) return;
                          Navigator.pop(dialogContext);
                          _showMessage(
                            'Registration successful. You can now sign in.',
                          );
                        } catch (_) {
                          updateDialog(() => registering = false);
                          _showMessage(
                            'We couldn’t register your account. Please try again.',
                          );
                        }
                      },
                style: FilledButton.styleFrom(
                  backgroundColor: _orange,
                  foregroundColor: Colors.white,
                ),
                child: registering
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Text('Register'),
              ),
            ],
          );
        },
      ),
    ).whenComplete(() {
      nameController.dispose();
      usernameController.dispose();
      passwordController.dispose();
    });
  }
}
