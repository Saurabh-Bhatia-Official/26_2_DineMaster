import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dine_master/core/theme/app_colors.dart';
import '../core/api/api_mode.dart';
import '../core/database/database_helper.dart';
import '../repositories/table_repository.dart';
import '../models/table_model.dart';
import '../services/sync_service.dart';
import '../services/backend_api_client.dart';

class OverviewScreen extends StatefulWidget {
  const OverviewScreen({super.key});

  @override
  State<OverviewScreen> createState() => _OverviewScreenState();
}

class _OverviewScreenState extends State<OverviewScreen> {
  bool _isLoading = true;
  String _username = 'User';

  double _totalSales = 0.0;
  int _liveOrdersCount = 0;
  int _occupiedTablesCount = 0;
  int _totalTablesCount = 0;
  int _lowStockCount = 0;
  List<Map<String, dynamic>> _recentOrders = [];

  StreamSubscription? _syncSubscription;

  @override
  void initState() {
    super.initState();
    _loadOverviewData();

    _syncSubscription = SyncService.instance.syncEvents.listen((_) {
      if (mounted) {
        _loadOverviewData();
      }
    });
  }

  @override
  void dispose() {
    _syncSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadOverviewData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final username = prefs.getString('username') ?? 'User';

      if (await ApiMode.useBackendApi()) {
        final restaurantId = DatabaseHelper.currentRestaurantId ?? 1;

        final results = await Future.wait([
          BackendApiClient().getJson(
            '/api/v1/dashboard/summary?branchId=$restaurantId',
          ),
          TableRepository().getTables(),
        ]);

        final summary = results[0];
        final metrics = summary is Map && summary['metrics'] is Map
            ? Map<String, dynamic>.from(summary['metrics'] as Map)
            : <String, dynamic>{};
        final revenue = metrics['revenue'] is Map
            ? (metrics['revenue']['value'] as num?)?.toDouble()
            : null;
        final lowStock = metrics['lowStockAlerts'] is Map
            ? ((metrics['lowStockAlerts']['value'] as num?)?.toInt() ?? 0)
            : 0;

        final tables = results[1] as List<TableModel>;
        final totalTables = tables.where((table) => table.isActive).length;
        final occupiedTables = tables
            .where((table) => table.isActive && table.isOccupied)
            .length;

        if (mounted) {
          setState(() {
            _username = username;
            _totalSales = revenue ?? 0;
            _lowStockCount = lowStock;
            _occupiedTablesCount = occupiedTables;
            _totalTablesCount = totalTables;
            _isLoading = false;
          });
        }
        return;
      }

      final db = await DatabaseHelper.instance.database;
      final restaurantId = DatabaseHelper.currentRestaurantId;

      final salesRes = await db.rawQuery(
        "SELECT SUM(total_amount) as total FROM orders WHERE restaurant_id = ? AND payment_status = 'Paid'",
        [restaurantId],
      );
      final totalSalesVal = salesRes.first['total'] as num?;
      final totalSales = totalSalesVal?.toDouble() ?? 0.0;

      final liveRes = await db.rawQuery(
        "SELECT COUNT(*) as count FROM orders WHERE restaurant_id = ? AND status IN ('Received', 'Sent to Kitchen', 'Preparing', 'Ready', 'Cooking', 'Billing Pending')",
        [restaurantId],
      );
      final liveOrders = liveRes.first['count'] as int? ?? 0;

      await TableRepository().syncAllTableStatuses(restaurantId);
      final occupiedRes = await db.rawQuery(
        "SELECT COUNT(*) as count FROM tables WHERE (restaurant_id = ? OR restaurant_id IS NULL) AND status = 'Occupied'",
        [restaurantId],
      );
      final occupied = occupiedRes.first['count'] as int? ?? 0;

      final totalTablesRes = await db.rawQuery(
        "SELECT COUNT(*) as count FROM tables WHERE restaurant_id = ?",
        [restaurantId],
      );
      final totalTables = totalTablesRes.first['count'] as int? ?? 0;

      final lowStockRes = await db.rawQuery(
        "SELECT COUNT(*) as count FROM inventory WHERE restaurant_id = ? AND current_stock > 0 AND current_stock <= low_stock_threshold",
        [restaurantId],
      );
      final lowStock = (lowStockRes.first['count'] as num?)?.toInt() ?? 0;

      final recentOrdersRes = await db.rawQuery(
        '''
        SELECT o.*, t.table_number,
               (SELECT COUNT(*) FROM order_items WHERE order_id = o.id) as item_count
        FROM orders o
        LEFT JOIN tables t ON o.table_id = t.id
        WHERE o.restaurant_id = ?
        ORDER BY o.order_time DESC
        LIMIT 5
      ''',
        [restaurantId],
      );

      if (mounted) {
        setState(() {
          _username = username;
          _totalSales = totalSales;
          _liveOrdersCount = liveOrders;
          _occupiedTablesCount = occupied;
          _totalTablesCount = totalTables;
          _lowStockCount = lowStock;
          _recentOrders = List<Map<String, dynamic>>.from(recentOrdersRes);
          _isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Error loading overview data: $e');
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Color _statusColor(String status) {
    switch (status) {
      case 'Preparing':
      case 'Cooking':
        return Colors.orange;
      case 'Ready':
        return Colors.blue;
      case 'Served':
      case 'Completed':
      case 'Paid':
        return Colors.green;
      case 'Received':
      case 'Sent to Kitchen':
        return Colors.indigo;
      case 'Held':
        return Colors.amber;
      case 'Cancelled':
        return Colors.red;
      default:
        return Colors.grey;
    }
  }

  String _formatOrderTime(String? isoString) {
    if (isoString == null || isoString.isEmpty) return '';
    try {
      final dt = DateTime.parse(isoString);
      return DateFormat('hh:mm a').format(dt);
    } catch (_) {
      return isoString;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    return Scaffold(
      body: RefreshIndicator(
        onRefresh: _loadOverviewData,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: EdgeInsets.all(
            MediaQuery.of(context).size.width < 600 ? 16 : 28,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildWelcomeBanner(
                context,
              ).animate().fadeIn().slideY(begin: -.06),
              const SizedBox(height: 24),

              // Metrics Grid
              GridView.count(
                crossAxisCount: MediaQuery.sizeOf(context).width >= 1360
                    ? 4
                    : MediaQuery.sizeOf(context).width >= 600
                    ? 2
                    : 1,
                childAspectRatio: MediaQuery.sizeOf(context).width >= 1360
                    ? 1.45
                    : MediaQuery.sizeOf(context).width >= 600
                    ? 1.65
                    : 2.35,
                crossAxisSpacing: 14,
                mainAxisSpacing: 14,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                children: [
                  _buildMetricCard(
                    'Total Sales',
                    '₹${_totalSales.toStringAsFixed(2)}',
                    Icons.currency_rupee_rounded,
                    AppColors.orange,
                    'Paid revenue today',
                  ),
                  _buildMetricCard(
                    'Live Orders',
                    '$_liveOrdersCount',
                    Icons.restaurant_rounded,
                    const Color(0xFFB95D2D),
                    'In preparation / active',
                  ),
                  _buildMetricCard(
                    'Occupied Tables',
                    '$_occupiedTablesCount/$_totalTablesCount',
                    Icons.table_bar_rounded,
                    const Color(0xFF64747D),
                    'Active seating layout',
                  ),
                  _buildMetricCard(
                    'Low Stock Items',
                    '$_lowStockCount',
                    Icons.inventory_2_rounded,
                    _lowStockCount > 0
                        ? const Color(0xFFD65A4A)
                        : AppColors.muted,
                    _lowStockCount > 0
                        ? 'Requires attention'
                        : 'Inventory healthy',
                  ),
                ],
              ).animate().fadeIn(delay: 200.ms),
              const SizedBox(height: 28),

              // Recent Orders Section
              const Text(
                'Recent Orders',
                style: TextStyle(
                  color: AppColors.ink,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -.35,
                ),
              ).animate().fadeIn(delay: 400.ms),
              const SizedBox(height: 16),

              if (_recentOrders.isEmpty)
                Card(
                  elevation: 0,
                  color: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: const BorderSide(color: AppColors.line),
                  ),
                  child: const Padding(
                    padding: EdgeInsets.symmetric(vertical: 48, horizontal: 24),
                    child: Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            Icons.receipt_long_outlined,
                            size: 48,
                            color: Colors.grey,
                          ),
                          SizedBox(height: 12),
                          Text(
                            'No recent orders',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                              color: Colors.grey,
                            ),
                          ),
                          SizedBox(height: 4),
                          Text(
                            'Orders placed via POS Billing or Waiter Ordering will appear here.',
                            style: TextStyle(fontSize: 13, color: Colors.grey),
                            textAlign: TextAlign.center,
                          ),
                        ],
                      ),
                    ),
                  ),
                ).animate().fadeIn(delay: 500.ms)
              else
                ListView.separated(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: _recentOrders.length,
                  separatorBuilder: (context, index) =>
                      const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final order = _recentOrders[index];
                    final orderId = order['id'];
                    final tableNum = order['table_number'] != null
                        ? 'Table ${order['table_number']}'
                        : (order['type'] ?? 'Walk-in');
                    final itemCount = order['item_count'] ?? 0;
                    final timeStr = _formatOrderTime(
                      order['order_time'] as String?,
                    );
                    final status = order['status'] as String? ?? 'Received';
                    final statusColor = _statusColor(status);

                    return Card(
                          elevation: 0,
                          color: Colors.white,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                            side: const BorderSide(color: AppColors.line),
                          ),
                          child: ListTile(
                            leading: CircleAvatar(
                              backgroundColor: AppColors.primaryLight,
                              child: const Icon(
                                Icons.receipt_long,
                                color: AppColors.primary,
                              ),
                            ),
                            title: Text(
                              'Order #$orderId',
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            subtitle: Text(
                              '$tableNum • $itemCount ${itemCount == 1 ? 'Item' : 'Items'} • $timeStr',
                            ),
                            trailing: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 6,
                              ),
                              decoration: BoxDecoration(
                                color: statusColor.withAlpha(25),
                                borderRadius: BorderRadius.circular(20),
                                border: Border.all(
                                  color: statusColor.withAlpha(50),
                                ),
                              ),
                              child: Text(
                                status,
                                style: TextStyle(
                                  color: statusColor,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ),
                        )
                        .animate()
                        .fadeIn(delay: (500 + (index * 100)).ms)
                        .slideX(begin: 0.1);
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildWelcomeBanner(BuildContext context) {
    final compact = MediaQuery.sizeOf(context).width < 600;
    final displayName = _username.trim().isEmpty ? 'there' : _username.trim();

    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [AppColors.charcoal, Color(0xFF343536), Color(0xFF463328)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1A202326),
            blurRadius: 24,
            offset: Offset(0, 12),
          ),
        ],
      ),
      child: Stack(
        children: [
          Positioned(
            right: compact ? -42 : 80,
            top: -90,
            child: Container(
              width: 230,
              height: 230,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: Colors.white.withValues(alpha: .055),
                  width: 34,
                ),
              ),
            ),
          ),
          Padding(
            padding: EdgeInsets.all(compact ? 20 : 26),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            width: 7,
                            height: 7,
                            decoration: const BoxDecoration(
                              color: Color(0xFF82C995),
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 8),
                          const Text(
                            'YOUR RESTAURANT, AT A GLANCE',
                            style: TextStyle(
                              color: Color(0xFFE8A783),
                              fontSize: 9,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 1.3,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 11),
                      Text(
                        compact
                            ? 'Welcome back,\n$displayName'
                            : 'Welcome back, $displayName',
                        maxLines: compact ? 2 : 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: compact ? 22 : 27,
                          height: 1.13,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -.65,
                        ),
                      ),
                      const SizedBox(height: 7),
                      const Text(
                        'Here is what is happening today.',
                        style: TextStyle(
                          color: Color(0xFFCFD0D0),
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                if (!compact) ...[
                  const SizedBox(width: 18),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 12,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: .08),
                      borderRadius: BorderRadius.circular(13),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: .12),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'TODAY',
                          style: TextStyle(
                            color: Color(0xFFB9BABC),
                            fontSize: 9,
                            fontWeight: FontWeight.w700,
                            letterSpacing: 1.1,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          DateFormat('EEE, d MMM').format(DateTime.now()),
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMetricCard(
    String title,
    String value,
    IconData icon,
    Color color,
    String subtitle,
  ) {
    var hovered = false;

    return StatefulBuilder(
      builder: (context, setCardState) => MouseRegion(
        onEnter: (_) => setCardState(() => hovered = true),
        onExit: (_) => setCardState(() => hovered = false),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          transform: Matrix4.translationValues(0, hovered ? -4 : 0, 0),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: hovered ? const Color(0xFFFFC5A5) : AppColors.line,
            ),
            boxShadow: [
              BoxShadow(
                color: hovered
                    ? AppColors.orange.withValues(alpha: .12)
                    : const Color(0x080D1117),
                blurRadius: hovered ? 22 : 12,
                offset: Offset(0, hovered ? 12 : 5),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(15),
            child: Stack(
              children: [
                Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  child: Container(
                    height: 3,
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [
                          color.withValues(alpha: .8),
                          color.withValues(alpha: .18),
                        ],
                      ),
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 21, 18, 17),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Flexible(
                            child: Text(
                              title,
                              style: const TextStyle(
                                color: AppColors.muted,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color: color.withValues(alpha: .10),
                              borderRadius: BorderRadius.circular(11),
                            ),
                            child: Icon(icon, color: color, size: 19),
                          ),
                        ],
                      ),
                      Text(
                        value,
                        style: const TextStyle(
                          fontSize: 25,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -.5,
                          color: AppColors.ink,
                        ),
                        overflow: TextOverflow.ellipsis,
                        maxLines: 1,
                      ),
                      Row(
                        children: [
                          Container(
                            width: 5,
                            height: 5,
                            decoration: BoxDecoration(
                              color: color,
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 7),
                          Expanded(
                            child: Text(
                              subtitle,
                              style: const TextStyle(
                                color: AppColors.muted,
                                fontSize: 11,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
