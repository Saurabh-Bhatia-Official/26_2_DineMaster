
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

  const DashboardScreen({super.key, required this.child});

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
          await RestaurantRepository().getRestaurants();

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
      width: 260,
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF1E1E2F), Color(0xFF0F0F1A)],
        ),
      ),
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.all(24),
            child: Row(
              children: [
                Image.asset('assets/images/logo.jpg', height: 40, width: 40),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'DINE MASTER',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1,
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
          ListTile(
            leading: const CircleAvatar(
              backgroundColor: Colors.white,
              child: Icon(Icons.person, color: AppColors.primary),
            ),
            title: Text(
              _role.replaceAll('_', ' '),
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 13,
              ),
            ),
            subtitle: Text(
              _username,
              style: const TextStyle(color: Colors.white70, fontSize: 11),
            ),
            trailing: IconButton(
              icon: const Icon(
                Icons.logout,
                color: Colors.white70,
                size: 20,
              ),
              onPressed: () async {
                await UserRepository().logout();

                if (context.mounted) {
                  context.go('/');
                }
              },
            ),
          ),
          const SizedBox(height: 12),
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
              backgroundColor: const Color(0xFF1E1E2F),
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
                  title: Text(
                    visibleNavItems.isEmpty || activeIndex < 0
                        ? 'Dine Master'
                        : visibleNavItems[activeIndex]['label'] as String,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  actions: [
                    if (_restaurants.isNotEmpty)
                      DropdownButtonHideUnderline(
                        child: DropdownButton<int>(
                          value: _selectedRestaurantId,
                          isDense: true,
                          items: _restaurants.map((restaurant) {
                            return DropdownMenuItem<int>(
                              value: (restaurant['id'] as num).toInt(),
                              child: ConstrainedBox(
                                constraints: BoxConstraints(
                                  maxWidth: isDesktop ? 180 : 120,
                                ),
                                child: Text(
                                  restaurant['name']?.toString() ?? '',
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w600,
                                    fontSize: 13,
                                  ),
                                ),
                              ),
                            );
                          }).toList(),
                          onChanged: (value) {
                            setState(() {
                              _selectedRestaurantId = value;

                              if (!kIsWeb) {
                                DatabaseHelper.currentRestaurantId = value;
                              }

                              if (value != null) {
                                ApiMode.setCurrentRestaurantId(value);
                              }
                            });
                          },
                        ),
                      ),
                    const SizedBox(width: 4),
                    IconButton(
                      icon: const Icon(Icons.notifications_outlined, size: 20),
                      padding: const EdgeInsets.all(8),
                      constraints: const BoxConstraints(),
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
                    color: const Color(0xFFF8F9FA),
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
          padding: const EdgeInsets.only(left: 20, top: 16, bottom: 8),
          child: Text(
            categoryName.toUpperCase(),
            style: const TextStyle(
              color: Colors.white38,
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
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
            child: ListTile(
              dense: true,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
              ),
              leading: Icon(
                isSelected
                    ? item['selectedIcon'] as IconData
                    : item['icon'] as IconData,
                color: isSelected ? Colors.white : Colors.white60,
                size: 20,
              ),
              title: Text(
                item['label'] as String,
                style: TextStyle(
                  color: isSelected ? Colors.white : Colors.white70,
                  fontSize: 13.5,
                  fontWeight:
                      isSelected ? FontWeight.bold : FontWeight.normal,
                ),
              ),
              selected: isSelected,
              selectedTileColor: Colors.white.withAlpha(25),
              onTap: () {
                if (!isDesktop) {
                  Navigator.of(context).pop();
                }

                context.go(item['route'] as String);
              },
            ),
          ),
        );
      }
    });

    return widgets;
  }
}
