import 'dart:async';

import 'package:flutter/material.dart';

import '../core/api/api_mode.dart';
import '../core/database/database_helper.dart';
import '../models/user_model.dart';
import '../repositories/restaurant_repository.dart';
import '../repositories/user_repository.dart';
import '../services/sync_service.dart';
import '../services/backend_api_client.dart';
import 'package:dine_master/core/theme/app_colors.dart';

class UserManagementScreen extends StatefulWidget {
  const UserManagementScreen({super.key});

  @override
  State<UserManagementScreen> createState() => _UserManagementScreenState();
}

class _UserManagementScreenState extends State<UserManagementScreen>
    with SingleTickerProviderStateMixin {
  List<Map<String, dynamic>> _users = [];
  bool _isLoading = true;
  bool _obscurePassword = true;
  String _searchQuery = '';

  late TabController _tabController;
  StreamSubscription? _syncSubscription;

  final UserRepository _userRepository = UserRepository();
  final RestaurantRepository _restaurantRepository = RestaurantRepository();
  List<Map<String, dynamic>> _branches = [];

  @override
  void initState() {
    super.initState();

    _tabController = TabController(length: 3, vsync: this);
    _loadUsers();
    _loadBranches();

    // Keep existing synchronization behavior on supported platforms.
    _syncSubscription = SyncService.instance.syncEvents.listen((event) {
      if (mounted) {
        _loadUsers();
      }
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    _syncSubscription?.cancel();
    super.dispose();
  }

  // Web always uses the backend API.
  // Desktop/mobile continue using the existing ApiMode preference.
  Future<bool> _useBackendApi() {
    return ApiMode.useBackendApi();
  }

  // Normalize roles returned by the API or stored in SQLite.
  // Examples: WAITER, Waiter, ROLE_WAITER -> Waiter.
  String _displayRole(String role) {
    final normalized = role
        .trim()
        .toUpperCase()
        .replaceAll(RegExp(r'[\s-]+'), '_')
        .replaceFirst(RegExp(r'^ROLE_'), '');

    switch (normalized) {
      case 'OWNER':
        return 'Owner';
      case 'MANAGER':
        return 'Manager';
      case 'CASHIER':
        return 'Cashier';
      case 'WAITER':
        return 'Waiter';
      case 'CHEF':
      case 'COOK':
        return 'Chef';
      case 'INVENTORY_MANAGER':
        return 'Inventory Manager';
      case 'STAFF':
      case 'EMPLOYEE':
        return 'Other Staff';
      default:
        return role.trim();
    }
  }

  bool _isActiveValue(dynamic value) {
    return value == true || value == 1 || value?.toString() == '1';
  }

  Future<void> _loadUsers() async {
    try {
      List<Map<String, dynamic>> users;

      if (await _useBackendApi()) {
        final result = await _userRepository.getUsers();
        users = result.map((user) {
          final data = user.toMap();
          data['role'] = _displayRole(user.role);
          data['is_active'] = user.isActive ? 1 : 0;
          return data;
        }).toList();
      } else {
        final db = await DatabaseHelper.instance.database;
        users = await db.query('users', orderBy: 'name ASC');
      }

      if (!mounted) return;

      setState(() {
        _users = users;
        _isLoading = false;
      });
    } catch (e) {
      debugPrint('Error loading users: $e');

      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _loadBranches() async {
    try {
      if (!await _useBackendApi()) return;
      final branches = await _restaurantRepository.getRestaurants();
      if (mounted) setState(() => _branches = branches);
    } catch (e) {
      debugPrint('Error loading branches: $e');
    }
  }

  Future<void> _showRolePermissions() async {
    if (!await _useBackendApi()) return;
    try {
      const api = BackendApiClient();
      final results = await Future.wait([
        api.getJson('/api/v1/roles'),
        api.getJson('/api/v1/roles/permissions'),
      ]);
      final roles = results[0] is List
          ? (results[0] as List).whereType<Map>()
              .map((role) => Map<String, dynamic>.from(role)).toList()
          : <Map<String, dynamic>>[];
      final permissions = results[1] is List
          ? (results[1] as List).map((value) => value.toString()).toList()
          : <String>[];
      if (roles.isEmpty || permissions.isEmpty || !mounted) return;

      await showDialog<void>(
        context: context,
        builder: (dialogContext) {
          var selectedRole = roles.first['code'].toString();
          var grants = (roles.first['permissions'] as List? ?? [])
              .map((value) => value.toString()).toSet();
          var saving = false;
          return StatefulBuilder(
            builder: (context, setDialogState) => AlertDialog(
              title: const Text('Role Permissions'),
              content: SizedBox(
                width: 520,
                height: 480,
                child: Column(
                  children: [
                    DropdownButtonFormField<String>(
                      value: selectedRole,
                      decoration: const InputDecoration(labelText: 'Role'),
                      items: roles.map((role) => DropdownMenuItem(
                        value: role['code'].toString(),
                        child: Text(role['name'].toString()),
                      )).toList(),
                      onChanged: (value) {
                        if (value == null) return;
                        final role = roles.firstWhere(
                          (item) => item['code'].toString() == value,
                        );
                        setDialogState(() {
                          selectedRole = value;
                          grants = (role['permissions'] as List? ?? [])
                              .map((item) => item.toString()).toSet();
                        });
                      },
                    ),
                    const SizedBox(height: 8),
                    Expanded(
                      child: ListView(
                        children: permissions.map((permission) => CheckboxListTile(
                          dense: true,
                          title: Text(permission),
                          value: grants.contains(permission),
                          onChanged: (value) => setDialogState(() {
                            if (value == true) {
                              grants.add(permission);
                            } else {
                              grants.remove(permission);
                            }
                          }),
                        )).toList(),
                      ),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: saving ? null : () => Navigator.pop(dialogContext),
                  child: const Text('Cancel'),
                ),
                ElevatedButton(
                  onPressed: saving ? null : () async {
                    setDialogState(() => saving = true);
                    try {
                      await api.patchJson(
                        '/api/v1/roles/$selectedRole/permissions',
                        {'permissions': grants.toList()},
                      );
                      if (dialogContext.mounted) Navigator.pop(dialogContext);
                    } catch (e) {
                      setDialogState(() => saving = false);
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('Could not save permissions: $e')),
                        );
                      }
                    }
                  },
                  child: saving
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Save'),
                ),
              ],
            ),
          );
        },
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not load role permissions: $e')),
        );
      }
    }
  }

  void _showUserDialog([Map<String, dynamic>? user]) {
    _obscurePassword = true;

    final isEditing = user != null;
    final nameController = TextEditingController(
      text: isEditing ? (user['name'] ?? '').toString() : '',
    );

    final usernameController = TextEditingController(
      text: isEditing ? (user['username'] ?? '').toString() : '',
    );

    // Keep the existing password unchanged unless the user edits it.
    final passwordController = TextEditingController(
      text: isEditing ? '********' : '',
    );

    final contactController = TextEditingController(
      text: isEditing
          ? (user['contact_details'] ?? user['contactDetails'] ?? '').toString()
          : '',
    );

    final shiftController = TextEditingController(
      text: isEditing
          ? (user['shift_timing'] ?? user['shiftTiming'] ?? '').toString()
          : '',
    );

    // Dropdown value must exactly match one of the displayed items.
    String role = isEditing
        ? _displayRole((user['role'] ?? 'Waiter').toString())
        : 'Waiter';

    bool isActive = isEditing
        ? _isActiveValue(user['is_active'] ?? user['active'])
        : true;
    final existingBranchIds = isEditing
        ? ((user['branch_ids'] ?? user['branchIds']) as List<dynamic>? ??
                  const [])
              .whereType<num>()
              .map((id) => id.toInt())
              .toList()
        : <int>[];
    final selectedBranchIds = {...existingBranchIds};
    if (selectedBranchIds.isEmpty && _branches.length == 1) {
      selectedBranchIds.add((_branches.first['id'] as num).toInt());
    }
    showDialog(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setStateDialog) => AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          title: Row(
            children: [
              Icon(
                isEditing
                    ? Icons.edit_outlined
                    : Icons.person_add_alt_1_outlined,
                color: AppColors.primary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  isEditing ? 'Edit Employee Profile' : 'Add New Employee',
                ),
              ),
            ],
          ),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: nameController,
                  decoration: const InputDecoration(
                    labelText: 'Full Name',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.badge_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: usernameController,
                  decoration: const InputDecoration(
                    labelText: 'Username',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.alternate_email),
                  ),
                  enabled: !isEditing,
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: passwordController,
                  decoration: InputDecoration(
                    labelText: isEditing
                        ? 'Password (Leave as-is to keep old)'
                        : 'Password',
                    border: const OutlineInputBorder(),
                    prefixIcon: const Icon(Icons.lock_outline),
                    suffixIcon: IconButton(
                      icon: Icon(
                        _obscurePassword
                            ? Icons.visibility
                            : Icons.visibility_off,
                      ),
                      onPressed: () {
                        setStateDialog(() {
                          _obscurePassword = !_obscurePassword;
                        });
                      },
                    ),
                  ),
                  obscureText: _obscurePassword,
                ),
                const SizedBox(height: 12),

                // All possible values are normalized before being assigned.
                DropdownButtonFormField<String>(
                  key: ValueKey(role),
                  initialValue: role,
                  items:
                      const [
                        'Owner',
                        'Manager',
                        'Cashier',
                        'Waiter',
                        'Chef',
                        'Inventory Manager',
                        'Other Staff',
                      ].map((r) {
                        return DropdownMenuItem<String>(
                          value: r,
                          child: Text(r),
                        );
                      }).toList(),
                  onChanged: (value) {
                    if (value != null) {
                      setStateDialog(() => role = value);
                    }
                  },
                  decoration: const InputDecoration(
                    labelText: 'Role',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.work_outline),
                  ),
                ),
                const SizedBox(height: 12),
                if (_branches.isNotEmpty) ...[
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Authorized branches'),
                  ),
                  ..._branches.map((branch) {
                    final branchId = (branch['id'] as num).toInt();
                    return CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        (branch['name'] ?? 'Branch $branchId').toString(),
                      ),
                      value: selectedBranchIds.contains(branchId),
                      onChanged: (selected) {
                        setStateDialog(() {
                          if (selected == true) {
                            selectedBranchIds.add(branchId);
                          } else {
                            selectedBranchIds.remove(branchId);
                          }
                        });
                      },
                    );
                  }),
                ],
                TextField(
                  controller: contactController,
                  decoration: const InputDecoration(
                    labelText: 'Contact Details',
                    hintText: 'e.g. +91 98765 43210',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.phone_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: shiftController,
                  decoration: const InputDecoration(
                    labelText: 'Shift Timing',
                    hintText: 'e.g. 09:00 AM - 05:00 PM',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.schedule_outlined),
                  ),
                ),
                const SizedBox(height: 8),
                SwitchListTile(
                  title: const Text('Status (Active)'),
                  value: isActive,
                  activeColor: Colors.green,
                  onChanged: (value) {
                    setStateDialog(() => isActive = value);
                  },
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () async {
                final name = nameController.text.trim();
                final username = usernameController.text.trim();
                final password = passwordController.text.trim();
                final contact = contactController.text.trim();
                final shift = shiftController.text.trim();

                if (name.isEmpty || username.isEmpty || password.isEmpty) {
                  return;
                }
                try {
                  if (await _useBackendApi()) {
                    final currentBranchId = await ApiMode.currentRestaurantId();
                    if (selectedBranchIds.isEmpty &&
                        currentBranchId != null &&
                        _branches.isEmpty) {
                      selectedBranchIds.add(currentBranchId);
                    }
                    if (selectedBranchIds.isEmpty && _branches.isNotEmpty) {
                      if (dialogContext.mounted) {
                        ScaffoldMessenger.of(dialogContext).showSnackBar(
                          const SnackBar(
                            content: Text('Assign at least one branch'),
                          ),
                        );
                      }
                      return;
                    }
                    final branchIds = selectedBranchIds.isNotEmpty
                        ? selectedBranchIds.toList()
                        : (currentBranchId == null
                              ? <int>[]
                              : [currentBranchId]);
                    final apiUser = UserModel(
                      id: isEditing ? (user['id'] as num?)?.toInt() : null,
                      name: name,
                      username: username,
                      password: password == '********' ? '' : password,
                      role: role,
                      isActive: isActive,
                      contactDetails: contact,
                      shiftTiming: shift,
                      createdAt: isEditing
                          ? (user['created_at'] ?? user['createdAt'] ?? '')
                                .toString()
                          : DateTime.now().toIso8601String(),
                      branchIds: branchIds,
                    );

                    if (isEditing) {
                      await _userRepository.updateUser(apiUser);
                    } else {
                      await _userRepository.addUser(apiUser);
                    }
                  } else {
                    final db = await DatabaseHelper.instance.database;

                    final Map<String, dynamic> data = {
                      'name': name,
                      'role': role,
                      'is_active': isActive ? 1 : 0,
                      'contact_details': contact,
                      'shift_timing': shift,
                    };

                    if (!isEditing) {
                      data['username'] = username;
                      data['password'] = DatabaseHelper.hashPassword(password);
                      data['created_at'] = DateTime.now().toIso8601String();

                      await db.insert('users', data);
                    } else {
                      if (password != '********') {
                        data['password'] = DatabaseHelper.hashPassword(
                          password,
                        );
                      }

                      await db.update(
                        'users',
                        data,
                        where: 'id = ?',
                        whereArgs: [user['id']],
                      );
                    }
                  }

                  if (!mounted || !dialogContext.mounted) return;

                  Navigator.pop(dialogContext);
                  await _loadUsers();
                  if (!isEditing && mounted) {
                    final normalizedRole = _displayRole(role);
                    final targetTab = normalizedRole == 'Chef'
                        ? 0
                        : normalizedRole == 'Waiter'
                        ? 1
                        : 2;
                    _tabController.animateTo(targetTab);
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('Employee added under $normalizedRole')),
                    );
                  }
                } catch (e) {
                  debugPrint('Error saving user: $e');

                  if (dialogContext.mounted) {
                    ScaffoldMessenger.of(dialogContext).showSnackBar(
                      SnackBar(content: Text('Unable to save employee: $e')),
                    );
                  }
                }
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.primary,
                foregroundColor: Colors.white,
              ),
              child: Text(isEditing ? 'Save Changes' : 'Add Employee'),
            ),
          ],
        ),
      ),
    );
  }

  void _confirmDelete(int id, String name) {
    showDialog(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.warning_amber_rounded, color: Colors.red),
            SizedBox(width: 8),
            Text('Confirm Delete'),
          ],
        ),
        content: Text(
          'Are you sure you want to delete employee "$name"? This cannot be undone.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () async {
              try {
                if (await _useBackendApi()) {
                  await _userRepository.deleteUser(id);
                } else {
                  final db = await DatabaseHelper.instance.database;
                  await db.delete('users', where: 'id = ?', whereArgs: [id]);
                }

                if (!mounted || !dialogContext.mounted) return;

                Navigator.pop(dialogContext);
                await _loadUsers();
              } catch (e) {
                debugPrint('Error deleting user: $e');

                if (dialogContext.mounted) {
                  ScaffoldMessenger.of(dialogContext).showSnackBar(
                    SnackBar(content: Text('Unable to delete employee: $e')),
                  );
                }
              }
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.red,
              foregroundColor: Colors.white,
            ),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
  }

  Future<void> _reactivateStaff(int id) async {
    try {
      await _userRepository.reactivateUser(id);
      await _loadUsers();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not reactivate staff: $e')),
      );
    }
  }

  Future<void> _showPasswordReset(int id, String name) async {
    final passwordController = TextEditingController();
    final reasonController = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Reset $name password'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: passwordController,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Temporary password (10+ characters)'),
            ),
            TextField(
              controller: reasonController,
              decoration: const InputDecoration(labelText: 'Reason (optional)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(dialogContext, true), child: const Text('Reset')),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await _userRepository.resetUserPassword(
        id,
        passwordController.text,
        reason: reasonController.text,
      );
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Temporary password set; staff must change it at next login.')),
      );
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not reset password: $e')),
      );
    }
  }

  Future<void> _showStaffRecords(int id, {required bool audit}) async {
    try {
      final response = audit
          ? await _userRepository.getUserAudit(id)
          : await _userRepository.getUserSessions(id);
      final entries = response is List ? response : <dynamic>[];
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(audit ? 'Staff audit history' : 'Active staff sessions'),
          content: SizedBox(
            width: 500,
            height: 360,
            child: entries.isEmpty
                ? const Center(child: Text('No records found'))
                : ListView.builder(
                    itemCount: entries.length,
                    itemBuilder: (context, index) {
                      final row = entries[index];
                      final map = row is Map ? Map<String, dynamic>.from(row) : <String, dynamic>{};
                      final primary = audit
                          ? (map['action'] ?? map['eventType'] ?? 'Audit event')
                          : (map['deviceId'] ?? 'Session');
                      final secondary = map.entries
                          .where((entry) => entry.key != 'action' && entry.key != 'eventType')
                          .map((entry) => '${entry.key}: ${entry.value}')
                          .join(' · ');
                      return ListTile(title: Text(primary.toString()), subtitle: Text(secondary));
                    },
                  ),
          ),
          actions: [
            if (!audit && entries.isNotEmpty)
              TextButton(
                onPressed: () async {
                  try {
                    await _userRepository.revokeUserSessions(id);
                    if (dialogContext.mounted) Navigator.pop(dialogContext);
                    await _loadUsers();
                  } catch (e) {
                    if (mounted) ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('Could not revoke sessions: $e')),
                    );
                  }
                },
                child: const Text('Revoke all'),
              ),
            TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('Close')),
          ],
        ),
      );
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not load staff records: $e')),
      );
    }
  }

  Widget _buildEmployeeList(List<Map<String, dynamic>> list) {
    final query = _searchQuery.toLowerCase();

    final filtered = list.where((employee) {
      final name = (employee['name'] ?? '').toString().toLowerCase();
      final username = (employee['username'] ?? '').toString().toLowerCase();

      return name.contains(query) || username.contains(query);
    }).toList();

    if (filtered.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.people_outline, size: 64, color: Colors.grey.shade400),
            const SizedBox(height: 16),
            Text(
              _searchQuery.isEmpty
                  ? 'No staff members registered in this category'
                  : 'No staff members match "$_searchQuery"',
              style: TextStyle(color: Colors.grey.shade600, fontSize: 16),
            ),
          ],
        ),
      );
    }

    final isDark = Theme.of(context).brightness == Brightness.dark;

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: filtered.length,
      itemBuilder: (context, index) {
        final employee = filtered[index];

        final isActive = _isActiveValue(
          employee['is_active'] ?? employee['active'],
        );

        final name = (employee['name'] ?? 'N/A').toString();
        final username = (employee['username'] ?? '').toString();
        final role = _displayRole((employee['role'] ?? '').toString());

        final contact =
            (employee['contact_details'] ?? employee['contactDetails'] ?? '')
                .toString();

        final shift =
            (employee['shift_timing'] ?? employee['shiftTiming'] ?? '')
                .toString();

        final initial = name.isNotEmpty
            ? name.substring(0, 1).toUpperCase()
            : 'S';

        final avatarGradient = isActive
            ? [AppColors.primary, Colors.indigo]
            : [Colors.grey, Colors.blueGrey];

        return Card(
          margin: const EdgeInsets.only(bottom: 12),
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: BorderSide(
              color: isDark ? Colors.grey.shade800 : Colors.grey.shade100,
              width: 1.5,
            ),
          ),
          color: isDark ? Colors.grey.shade900 : Colors.white,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 52,
                  height: 52,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: LinearGradient(
                      colors: avatarGradient,
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: avatarGradient.first.withValues(alpha: 0.3),
                        blurRadius: 8,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Center(
                    child: Text(
                      initial,
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                        fontSize: 20,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              name,
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 17,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 3,
                            ),
                            decoration: BoxDecoration(
                              color: isActive
                                  ? Colors.green.withValues(alpha: 0.1)
                                  : Colors.red.withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(
                                color: isActive
                                    ? Colors.green.withValues(alpha: 0.3)
                                    : Colors.red.withValues(alpha: 0.3),
                              ),
                            ),
                            child: Text(
                              isActive ? 'Active' : 'Inactive',
                              style: TextStyle(
                                color: isActive
                                    ? Colors.green.shade800
                                    : Colors.red.shade800,
                                fontSize: 10,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '@$username  |  $role',
                        style: TextStyle(
                          color: Colors.grey.shade600,
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Icon(
                            Icons.phone_outlined,
                            size: 14,
                            color: AppColors.primaryMaterialColor[300]!,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              contact.isNotEmpty
                                  ? contact
                                  : 'No contact details',
                              style: TextStyle(
                                color: Colors.grey.shade700,
                                fontSize: 12,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Row(
                        children: [
                          Icon(
                            Icons.schedule_outlined,
                            size: 14,
                            color: AppColors.primaryMaterialColor[300]!,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              shift.isNotEmpty ? shift : 'No shift assigned',
                              style: TextStyle(
                                color: Colors.grey.shade700,
                                fontSize: 12,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Column(
                  children: [
                    IconButton(
                      icon: const Icon(
                        Icons.edit_outlined,
                        color: Colors.blue,
                        size: 20,
                      ),
                      onPressed: () => _showUserDialog(employee),
                    ),
                    IconButton(
                      icon: const Icon(
                        Icons.delete_outline,
                        color: Colors.red,
                        size: 20,
                      ),
                      onPressed: () {
                        final id = (employee['id'] as num?)?.toInt();
                        if (id != null) {
                          _confirmDelete(id, name);
                        }
                      },
                    ),
                    PopupMenuButton<String>(
                      tooltip: 'More staff actions',
                      onSelected: (action) {
                        final id = (employee['id'] as num?)?.toInt();
                        if (id == null) return;
                        switch (action) {
                          case 'reactivate':
                            _reactivateStaff(id);
                            break;
                          case 'reset_password':
                            _showPasswordReset(id, name);
                            break;
                          case 'sessions':
                            _showStaffRecords(id, audit: false);
                            break;
                          case 'audit':
                            _showStaffRecords(id, audit: true);
                            break;
                        }
                      },
                      itemBuilder: (context) => [
                        if (!isActive)
                          const PopupMenuItem(
                            value: 'reactivate',
                            child: Text('Reactivate'),
                          ),
                        const PopupMenuItem(
                          value: 'reset_password',
                          child: Text('Reset password'),
                        ),
                        const PopupMenuItem(
                          value: 'sessions',
                          child: Text('View / revoke sessions'),
                        ),
                        const PopupMenuItem(
                          value: 'audit',
                          child: Text('Audit history'),
                        ),
                      ],
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    // Normalize role names before filtering so API roles like CHEF/WAITER
    // appear in the correct tabs.
    final chefs = _users.where((employee) {
      return _displayRole((employee['role'] ?? '').toString()) == 'Chef';
    }).toList();

    final waiters = _users.where((employee) {
      return _displayRole((employee['role'] ?? '').toString()) == 'Waiter';
    }).toList();

    final otherStaff = _users.where((employee) {
      final role = _displayRole((employee['role'] ?? '').toString());

      return role != 'Chef' && role != 'Waiter';
    }).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Staff & User Onboarding',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        backgroundColor: isDark ? null : Colors.white,
        foregroundColor: isDark ? null : Colors.black,
        elevation: 0,
        actions: [
          IconButton(
            tooltip: 'Role permissions',
            onPressed: _showRolePermissions,
            icon: const Icon(Icons.admin_panel_settings_outlined),
          ),
        ],
        bottom: TabBar(
          controller: _tabController,
          labelColor: AppColors.primary,
          unselectedLabelColor: Colors.grey,
          indicatorColor: AppColors.primary,
          tabs: const [
            Tab(icon: Icon(Icons.restaurant), text: 'Chefs'),
            Tab(icon: Icon(Icons.directions_walk), text: 'Waiters'),
            Tab(
              icon: Icon(Icons.admin_panel_settings_outlined),
              text: 'Other Staff',
            ),
          ],
        ),
      ),
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(color: AppColors.primary),
            )
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 12,
                  ),
                  child: TextField(
                    decoration: InputDecoration(
                      hintText: 'Search staff by name or username...',
                      prefixIcon: const Icon(
                        Icons.search,
                        color: AppColors.primary,
                      ),
                      fillColor: isDark
                          ? Colors.grey.shade900
                          : Colors.grey.shade100,
                      filled: true,
                      contentPadding: const EdgeInsets.symmetric(vertical: 12),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide.none,
                      ),
                    ),
                    onChanged: (value) {
                      setState(() {
                        _searchQuery = value;
                      });
                    },
                  ),
                ),
                Expanded(
                  child: TabBarView(
                    controller: _tabController,
                    children: [
                      _buildEmployeeList(chefs),
                      _buildEmployeeList(waiters),
                      _buildEmployeeList(otherStaff),
                    ],
                  ),
                ),
              ],
            ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showUserDialog(),
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        icon: const Icon(Icons.person_add_alt_1_outlined),
        label: const Text(
          'Onboard Staff',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
      ),
    );
  }
}
