import 'dart:convert';

import 'package:dine_master/core/theme/app_colors.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../core/api/api_mode.dart';
import '../core/database/database_helper.dart';
import '../repositories/restaurant_repository.dart';
import '../repositories/user_repository.dart';
import '../services/backend_api_client.dart';

class DashboardScreen extends StatefulWidget {
  final Widget child;
  final Future<List<Map<String, dynamic>>> Function()? restaurantsLoader;

  const DashboardScreen({
    super.key,
    required this.child,
    this.restaurantsLoader,
  });

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  List<Map<String, dynamic>> _restaurants = [];
  int? _selectedRestaurantId;
  String _username = 'User';
  String _role = 'STAFF';
  Set<String> _permissions = {};
  bool _accessLoaded = false;

  @override
  void initState() {
    super.initState();
    _loadRestaurants();
    _loadUserAccess();
  }

  Future<void> _loadUserAccess() async {
    final prefs = await SharedPreferences.getInstance();
    var role = RoleNames.canonical(prefs.getString('role') ?? 'STAFF');
    var permissions = <String>{};
    var permissionsResolved = false;
    final saved = prefs.getString('permissions');
    if (saved != null) {
      try {
        permissions = (jsonDecode(saved) as List)
            .map((permission) => permission.toString())
            .toSet();
        permissionsResolved = true;
      } catch (_) {
        permissions = {};
      }
    }
    final backendMode = await ApiMode.useBackendApi();
    if (!permissionsResolved && backendMode) {
      try {
        final response = await const BackendApiClient().getJson(
          '/api/v1/auth/me/permissions',
        );
        if (response is Map && response['permissions'] is List) {
          permissions = (response['permissions'] as List)
              .map((permission) => permission.toString())
              .toSet();
          await prefs.setString('permissions', jsonEncode(permissions.toList()));
          permissionsResolved = true;
        }
      } catch (e) {
        debugPrint('Could not load role permissions: $e');
      }
    }
    if (!permissionsResolved && !backendMode) {
      permissions = _defaultPermissions(role);
    }

    if (!mounted) return;

    setState(() {
      _username = prefs.getString('username') ?? 'User';
      _role = role;
      _permissions = permissions;
      _accessLoaded = true;
    });
  }

  Set<String> _defaultPermissions(String role) {
    switch (RoleNames.canonical(role)) {
      case 'OWNER':
      case 'MANAGER':
        return {
          'dashboard:view', 'table:view', 'order:create', 'order:view',
          'kot:view', 'bill:generate', 'menu:manage', 'menu:view',
          'inventory:view', 'expense:view', 'customer:view', 'user:view',
          'branch:view', 'settings:view', 'report:sales', 'report:kitchen',
          'report:inventory', 'report:finance',
        };
      case 'CASHIER':
        return {
          'table:view', 'order:create', 'order:view', 'kot:view',
          'bill:generate', 'menu:view', 'customer:view', 'report:sales',
        };
      case 'WAITER':
        return {
          'table:view',
          'order:create',
          'order:view',
          'kot:view',
          'customer:view',
        };
      case 'CHEF':
        return {
          'dashboard:view',
          'kot:view',
          'order:view',
          'menu:view',
          'inventory:view',
          'report:kitchen',
        };
      case 'INVENTORY_MANAGER':
        return {'dashboard:view', 'inventory:view', 'menu:view', 'report:inventory', 'branch:view'};
      default:
        return {};
    }
  }

  bool _canOpen(Map<String, dynamic> item) {
    final required = item['permissions'] as List<String>;
    return required.any(_permissions.contains);
  }

  Future<void> _loadRestaurants() async {
    try {
      final List<Map<String, dynamic>> restaurants =
          await (widget.restaurantsLoader?.call() ??
              RestaurantRepository().getRestaurants());

      if (!mounted) return;

      setState(() {
        _restaurants = restaurants;

        if (restaurants.isNotEmpty) {
          final firstId = (restaurants.first['id'] as num).toInt();
          final savedId = DatabaseHelper.currentRestaurantId;

          _selectedRestaurantId =
              restaurants.any(
                (restaurant) => (restaurant['id'] as num).toInt() == savedId,
              )
              ? savedId
              : firstId;

          DatabaseHelper.currentRestaurantId = _selectedRestaurantId;

          if (_selectedRestaurantId != null) {
            ApiMode.setCurrentRestaurantId(_selectedRestaurantId!);
          }
        } else {
          _selectedRestaurantId = null;
        }
      });
    } catch (e) {
      debugPrint('Failed to load restaurants: $e');
    }
  }

  final List<Map<String, dynamic>> _navItems = [
    {
      'icon': Icons.dashboard_outlined,
      'selectedIcon': Icons.dashboard,
      'label': 'Dashboard',
      'route': '/dashboard',
      'category': 'Overview',
      'permissions': <String>['dashboard:view'],
    },
    {
      'icon': Icons.table_bar_outlined,
      'selectedIcon': Icons.table_bar,
      'label': 'Tables',
      'route': '/dashboard/tables',
      'category': 'Operations',
      'permissions': <String>['table:view'],
    },
    {
      'icon': Icons.assignment_turned_in_outlined,
      'selectedIcon': Icons.assignment_turned_in,
      'label': 'Waiter Ordering',
      'route': '/dashboard/waiter_order',
      'category': 'Operations',
      'permissions': <String>['order:create'],
    },
    {
      'icon': Icons.kitchen_outlined,
      'selectedIcon': Icons.kitchen,
      'label': 'Kitchen KOT',
      'route': '/dashboard/kitchen',
      'category': 'Operations',
      'permissions': <String>['kot:view'],
    },
    {
      'icon': Icons.track_changes,
      'selectedIcon': Icons.track_changes,
      'label': 'Live Tracking',
      'route': '/dashboard/live_tracking',
      'category': 'Operations',
      'permissions': <String>['order:view'],
    },
    {
      'icon': Icons.point_of_sale_outlined,
      'selectedIcon': Icons.point_of_sale,
      'label': 'POS Billing',
      'route': '/dashboard/pos',
      'category': 'Billing',
      'permissions': <String>['bill:generate'],
    },
    {
      'icon': Icons.history_outlined,
      'selectedIcon': Icons.history,
      'label': 'Order History',
      'route': '/dashboard/orders',
      'category': 'Billing',
      'permissions': <String>['order:view'],
    },
    {
      'icon': Icons.restaurant_menu_outlined,
      'selectedIcon': Icons.restaurant_menu,
      'label': 'Food Products',
      'route': '/dashboard/products_manage',
      'category': 'Management',
      'permissions': <String>['menu:manage'],
    },
    {
      'icon': Icons.menu_book_outlined,
      'selectedIcon': Icons.menu_book,
      'label': 'Menu Card',
      'route': '/dashboard/menu_card',
      'category': 'Management',
      'permissions': <String>['menu:view'],
    },
    {
      'icon': Icons.inventory_2_outlined,
      'selectedIcon': Icons.inventory,
      'label': 'Inventory',
      'route': '/dashboard/inventory',
      'category': 'Management',
      'permissions': <String>['inventory:view'],
    },
    {
      'icon': Icons.account_balance_outlined,
      'selectedIcon': Icons.account_balance,
      'label': 'Accounts',
      'route': '/dashboard/accounts',
      'category': 'Management',
      'permissions': <String>['expense:view', 'report:finance'],
    },
    {
      'icon': Icons.people_alt_outlined,
      'selectedIcon': Icons.people_alt,
      'label': 'Customers',
      'route': '/dashboard/customers',
      'category': 'Management',
      'permissions': <String>['customer:view'],
    },
    {
      'icon': Icons.analytics_outlined,
      'selectedIcon': Icons.analytics,
      'label': 'Analytics',
      'route': '/dashboard/analytics',
      'category': 'Admin',
      'permissions': <String>['report:sales', 'report:kitchen', 'report:inventory', 'report:finance'],
    },
    {
      'icon': Icons.people_outline,
      'selectedIcon': Icons.people,
      'label': 'Staff Management',
      'route': '/dashboard/user_management',
      'category': 'Admin',
      'permissions': <String>['user:view'],
    },
    {
      'icon': Icons.restaurant_outlined,
      'selectedIcon': Icons.restaurant,
      'label': 'Restaurants',
      'route': '/dashboard/restaurants',
      'category': 'Admin',
      'permissions': <String>['branch:view'],
    },
    {
      'icon': Icons.settings_outlined,
      'selectedIcon': Icons.settings,
      'label': 'Settings',
      'route': '/dashboard/settings',
      'category': 'Admin',
      'permissions': <String>['settings:view'],
    },
  ];

  Widget _buildSidebar(BuildContext context, int activeIndex) {
    final visibleNavItems = _navItems.where(_canOpen).toList();
    return Container(
      width: 272,
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF292B2D), Color(0xFF191B1D)],
        ),
      ),
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.fromLTRB(22, 24, 20, 22),
            child: Row(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(11),
                  child: Image.asset(
                    'assets/images/logo.jpg',
                    height: 42,
                    width: 42,
                    fit: BoxFit.cover,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'DINE MASTER',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                          letterSpacing: .8,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        'Hi, $_username',
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 12,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const Divider(color: Colors.white12, height: 1),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.symmetric(vertical: 12),
              children: _buildNavSections(
                context,
                activeIndex,
                visibleNavItems,
              ),
            ),
          ),
          const Divider(color: Colors.white12, height: 1),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: .055),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: Colors.white.withValues(alpha: .08)),
              ),
              child: ListTile(
                dense: true,
                leading: const CircleAvatar(
                  radius: 18,
                  backgroundColor: Colors.white,
                  child: Icon(
                    Icons.person_rounded,
                    color: AppColors.primary,
                    size: 19,
                  ),
                ),
                title: const Text(
                  'Owner Account',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w700,
                    fontSize: 12,
                  ),
                ),
                subtitle: const Text(
                  'System Admin',
                  style: TextStyle(color: Colors.white60, fontSize: 10),
                ),
                trailing: IconButton(
                  tooltip: 'Log out',
                  icon: const Icon(
                    Icons.logout_rounded,
                    color: Colors.white70,
                    size: 18,
                  ),
                  onPressed: () async {
                    await UserRepository().logout();

                    if (context.mounted) {
                      context.go('/');
                    }
                  },
                ),
              ),
            ),
          ),
          const SizedBox(height: 10),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDesktop = MediaQuery.of(context).size.width >= 1024;
    final String location = GoRouterState.of(context).uri.path;
    final visibleNavItems = _navItems.where(_canOpen).toList();

    int activeIndex = visibleNavItems.indexWhere(
      (item) => item['route'] == location,
    );

    if (activeIndex == -1) {
      int bestMatchLength = 0;

      for (int i = 0; i < visibleNavItems.length; i++) {
        final route = visibleNavItems[i]['route'] as String;

        if (location.startsWith(route) && route.length > bestMatchLength) {
          bestMatchLength = route.length;
          activeIndex = i;
        }
      }
    }

    if (activeIndex == -1 && visibleNavItems.isNotEmpty) {
      activeIndex = 0;
      if (_accessLoaded && location.startsWith('/dashboard')) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) context.go(visibleNavItems.first['route'] as String);
        });
      }
    }

    return Scaffold(
      drawer: !isDesktop
          ? Drawer(
              backgroundColor: AppColors.charcoalDeep,
              child: _buildSidebar(context, activeIndex),
            )
          : null,
      body: Row(
        children: [
          if (isDesktop) _buildSidebar(context, activeIndex),
          Expanded(
            child: Column(
              children: [
                AppBar(
                  leading: !isDesktop
                      ? Builder(
                          builder: (context) => IconButton(
                            icon: const Icon(Icons.menu),
                            onPressed: () {
                              Scaffold.of(context).openDrawer();
                            },
                          ),
                        )
                      : null,
                  title: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _navItems[activeIndex]['label'] as String,
                        style: const TextStyle(
                          color: AppColors.ink,
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -.25,
                        ),
                      ),
                      if (isDesktop)
                        const Text(
                          'OWNER WORKSPACE',
                          style: TextStyle(
                            color: AppColors.muted,
                            fontSize: 8,
                            fontWeight: FontWeight.w700,
                            letterSpacing: 1.4,
                          ),
                        ),
                    ],
                  ),
                  flexibleSpace: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.white,
                      border: Border(
                        bottom: BorderSide(
                          color: AppColors.line.withValues(alpha: .8),
                        ),
                      ),
                    ),
                  ),
                  actions: [
                    if (_restaurants.isNotEmpty)
                      Container(
                        margin: const EdgeInsets.symmetric(vertical: 15),
                        padding: const EdgeInsets.only(left: 10, right: 4),
                        decoration: BoxDecoration(
                          color: const Color(0xFFFAF8F6),
                          borderRadius: BorderRadius.circular(11),
                          border: Border.all(color: AppColors.line),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              Icons.storefront_outlined,
                              color: AppColors.orange,
                              size: 17,
                            ),
                            const SizedBox(width: 6),
                            DropdownButtonHideUnderline(
                              child: DropdownButton<int>(
                                value: _selectedRestaurantId,
                                isDense: true,
                                iconSize: 17,
                                borderRadius: BorderRadius.circular(12),
                                items: _restaurants.map((restaurant) {
                                  return DropdownMenuItem<int>(
                                    value: (restaurant['id'] as num).toInt(),
                                    child: ConstrainedBox(
                                      constraints: BoxConstraints(
                                        maxWidth: isDesktop ? 176 : 104,
                                      ),
                                      child: Text(
                                        restaurant['name']?.toString() ?? '',
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(
                                          color: AppColors.ink,
                                          fontWeight: FontWeight.w600,
                                          fontSize: 12,
                                        ),
                                      ),
                                    ),
                                  );
                                }).toList(),
                                onChanged: (value) {
                                  setState(() {
                                    _selectedRestaurantId = value;

                                    if (!kIsWeb) {
                                      DatabaseHelper.currentRestaurantId =
                                          value;
                                    }

                                    if (value != null) {
                                      ApiMode.setCurrentRestaurantId(value);
                                    }
                                  });
                                },
                              ),
                            ),
                          ],
                        ),
                      ),
                    const SizedBox(width: 8),
                    IconButton(
                      tooltip: 'Notifications',
                      icon: const Icon(
                        Icons.notifications_none_rounded,
                        size: 21,
                      ),
                      style: IconButton.styleFrom(
                        backgroundColor: const Color(0xFFFAF8F6),
                        foregroundColor: AppColors.ink,
                        side: const BorderSide(color: AppColors.line),
                      ),
                      onPressed: () {},
                    ),
                    if (!isDesktop)
                      IconButton(
                        icon: const Icon(Icons.logout, size: 20),
                        padding: const EdgeInsets.all(8),
                        constraints: const BoxConstraints(),
                        tooltip: 'Logout',
                        onPressed: () async {
                          await UserRepository().logout();

                          if (context.mounted) {
                            context.go('/');
                          }
                        },
                      ),
                    const SizedBox(width: 6),
                  ],
                ),
                Expanded(
                  child: Container(
                    color: AppColors.background,
                    child: KeyedSubtree(
                      key: ValueKey(_selectedRestaurantId),
                      child: !_accessLoaded
                          ? const Center(child: CircularProgressIndicator())
                          : visibleNavItems.isEmpty
                          ? const Center(
                              child: Text(
                                'Your account does not have access to any screens.',
                              ),
                            )
                          : widget.child,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _buildNavSections(
    BuildContext context,
    int activeIndex,
    List<Map<String, dynamic>> visibleNavItems,
  ) {
    final isDesktop = MediaQuery.of(context).size.width >= 1024;
    final Map<String, List<int>> categories = {};

    for (int i = 0; i < visibleNavItems.length; i++) {
      final cat = visibleNavItems[i]['category'] ?? 'General';
      categories.putIfAbsent(cat as String, () => []).add(i);
    }

    final List<Widget> widgets = [];

    categories.forEach((categoryName, indices) {
      widgets.add(
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 17, 14, 8),
          child: Text(
            categoryName.toUpperCase(),
            style: const TextStyle(
              color: Color(0xFF9A9C9E),
              fontSize: 10,
              fontWeight: FontWeight.bold,
              letterSpacing: 1.5,
            ),
          ),
        ),
      );

      for (final index in indices) {
        final item = visibleNavItems[index];
        final isSelected = activeIndex == index;

        widgets.add(
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 2),
            child: Container(
              decoration: isSelected
                  ? BoxDecoration(
                      gradient: LinearGradient(
                        colors: [
                          AppColors.orange.withValues(alpha: .24),
                          AppColors.orange.withValues(alpha: .10),
                        ],
                      ),
                      borderRadius: BorderRadius.circular(11),
                      border: Border.all(
                        color: AppColors.orange.withValues(alpha: .24),
                      ),
                    )
                  : null,
              child: ListTile(
                dense: true,
                minVerticalPadding: 2,
                contentPadding: const EdgeInsets.symmetric(horizontal: 12),
                minLeadingWidth: 30,
                horizontalTitleGap: 9,
                visualDensity: const VisualDensity(vertical: -1),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(11),
                ),
                leading: Icon(
                  isSelected
                      ? item['selectedIcon'] as IconData
                      : item['icon'] as IconData,
                  color: isSelected ? const Color(0xFFFFA476) : Colors.white60,
                  size: 19,
                ),
                title: Text(
                  item['label'] as String,
                  style: TextStyle(
                    color: isSelected ? Colors.white : Colors.white70,
                    fontSize: 12.5,
                    fontWeight: isSelected
                        ? FontWeight.bold
                        : FontWeight.normal,
                  ),
                ),
                selected: isSelected,
                selectedTileColor: Colors.transparent,
                splashColor: AppColors.orange.withValues(alpha: .16),
                hoverColor: Colors.white.withValues(alpha: .07),
                trailing: isSelected
                    ? const Icon(
                        Icons.circle,
                        color: Color(0xFFFFA476),
                        size: 6,
                      )
                    : null,
                onTap: () {
                  if (!isDesktop) {
                    Navigator.of(context).pop();
                  }

                  context.go(item['route'] as String);
                },
              ),
            ),
          ),
        );
      }
    });

    return widgets;
  }
}
