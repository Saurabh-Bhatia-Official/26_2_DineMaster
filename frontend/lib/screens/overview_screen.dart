import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dine_master/core/theme/app_colors.dart';
import '../core/api/api_mode.dart';
import '../core/database/database_helper.dart';
import '../models/order_model.dart';
import '../models/table_model.dart';
import '../repositories/order_api_repository.dart';
import '../repositories/table_repository.dart';
import '../services/backend_api_client.dart';
import '../services/sync_service.dart';

class OverviewScreen extends StatefulWidget {
  const OverviewScreen({super.key});

  @override
  State<OverviewScreen> createState() => _OverviewScreenState();
}

class _OverviewScreenState extends State<OverviewScreen> {
  bool _isLoading = true;
  bool _revenueVisible = true;
  bool _hasTrend = false;
  String _username = 'there';
  int _rangeDays = 7;
  double _totalSales = 0;
  double? _averageOrder;
  int _liveOrdersCount = 0;
  int _occupiedTablesCount = 0;
  int _totalTablesCount = 0;
  int _lowStockCount = 0;
  List<double?> _trend = [];
  List<OrderModel> _recentOrders = [];
  StreamSubscription? _syncSubscription;

  @override
  void initState() {
    super.initState();
    _loadOverviewData();
    _syncSubscription = SyncService.instance.syncEvents.listen((_) {
      if (mounted) _loadOverviewData();
    });
  }

  @override
  void dispose() {
    _syncSubscription?.cancel();
    super.dispose();
  }

  double? _metric(Map<dynamic, dynamic> metrics, String key) {
    final item = metrics[key];
    if (item is Map && item['visible'] == false) return null;
    final value = item is Map ? item['value'] : item;
    return value is num ? value.toDouble() : double.tryParse('$value');
  }

  Future<Map<String, dynamic>> _summaryFor(int branchId, DateTime date) async {
    final key = DateFormat('yyyy-MM-dd').format(date);
    final response = await BackendApiClient().getJson(
      '/api/v1/dashboard/summary?branchId=$branchId&date=$key',
    );
    return response is Map ? Map<String, dynamic>.from(response) : {};
  }

  Future<void> _loadOverviewData() async {
    if (mounted) setState(() => _isLoading = true);
    try {
      final prefs = await SharedPreferences.getInstance();
      final username = prefs.getString('username') ?? 'there';
      final branchId = DatabaseHelper.currentRestaurantId;
      if (branchId == null) throw StateError('No branch selected');

      if (await ApiMode.useBackendApi()) {
        final today = DateTime.now();
        final responses = await Future.wait([
          _summaryFor(branchId, today),
          OrderApiRepository().getOrders(branchId),
          TableRepository().getTables(),
          ...List.generate(
            _rangeDays,
            (i) => _summaryFor(
              branchId,
              today.subtract(Duration(days: _rangeDays - 1 - i)),
            ),
          ),
        ]);
        final summary = responses.first as Map<String, dynamic>;
        final metrics = summary['metrics'] is Map
            ? summary['metrics'] as Map
            : <dynamic, dynamic>{};
        final revenue = _metric(metrics, 'revenue');
        final avg = _metric(metrics, 'averageOrderValue');
        final lowStock = _metric(metrics, 'lowStockAlerts');
        final orders = responses[1] as List<OrderModel>;
        final tables = responses[2] as List<TableModel>;
        final activeTables = tables.where((table) => table.isActive).toList();
        final series = responses.skip(3).map((raw) {
          final map = raw as Map<String, dynamic>;
          final values = map['metrics'] is Map
              ? map['metrics'] as Map
              : <dynamic, dynamic>{};
          return _metric(values, 'revenue');
        }).toList();
        if (!mounted) return;
        setState(() {
          _username = username;
          _revenueVisible = revenue != null;
          _totalSales = revenue ?? 0;
          _averageOrder = avg;
          _lowStockCount = lowStock?.toInt() ?? 0;
          _occupiedTablesCount = activeTables.where((t) => t.isOccupied).length;
          _totalTablesCount = activeTables.length;
          _recentOrders = orders
            ..sort((a, b) => b.orderTime.compareTo(a.orderTime));
          _recentOrders = _recentOrders.take(5).toList();
          _liveOrdersCount = orders
              .where(
                (o) => const {
                  'Received',
                  'Sent to Kitchen',
                  'Preparing',
                  'Cooking',
                  'Ready',
                  'Billing Pending',
                }.contains(o.status),
              )
              .length;
          _trend = series;
          _hasTrend = series.any((value) => value != null);
          _isLoading = false;
        });
      } else {
        final db = await DatabaseHelper.instance.database;
        final salesRes = await db.rawQuery(
          "SELECT SUM(total_amount) as total FROM orders WHERE restaurant_id = ? AND payment_status = 'Paid'",
          [branchId],
        );
        final totalSales = (salesRes.first['total'] as num?)?.toDouble() ?? 0;
        final liveRes = await db.rawQuery(
          "SELECT COUNT(*) as count FROM orders WHERE restaurant_id = ? AND status IN ('Received', 'Sent to Kitchen', 'Preparing', 'Ready', 'Cooking', 'Billing Pending')",
          [branchId],
        );
        await TableRepository().syncAllTableStatuses(branchId);
        final occupiedRes = await db.rawQuery(
          "SELECT COUNT(*) as count FROM tables WHERE (restaurant_id = ? OR restaurant_id IS NULL) AND status = 'Occupied'",
          [branchId],
        );
        final totalTablesRes = await db.rawQuery(
          "SELECT COUNT(*) as count FROM tables WHERE restaurant_id = ? AND status != 'Inactive'",
          [branchId],
        );
        final lowStockRes = await db.rawQuery(
          'SELECT COUNT(*) as count FROM inventory WHERE restaurant_id = ? AND current_stock > 0 AND current_stock <= low_stock_threshold',
          [branchId],
        );
        final rawOrders = await db.rawQuery(
          '''
          SELECT o.*, t.table_number,
            (SELECT COUNT(*) FROM order_items WHERE order_id = o.id) as item_count
          FROM orders o LEFT JOIN tables t ON o.table_id = t.id
          WHERE o.restaurant_id = ? ORDER BY o.order_time DESC LIMIT 5
        ''',
          [branchId],
        );
        if (!mounted) return;
        setState(() {
          _username = username;
          _totalSales = totalSales;
          _revenueVisible = true;
          _liveOrdersCount = (liveRes.first['count'] as num?)?.toInt() ?? 0;
          _occupiedTablesCount =
              (occupiedRes.first['count'] as num?)?.toInt() ?? 0;
          _totalTablesCount =
              (totalTablesRes.first['count'] as num?)?.toInt() ?? 0;
          _lowStockCount = (lowStockRes.first['count'] as num?)?.toInt() ?? 0;
          _recentOrders = rawOrders
              .map((row) => OrderModel.fromMap(row))
              .toList();
          _trend = [];
          _hasTrend = false;
          _isLoading = false;
        });
      }
    } catch (error) {
      debugPrint('Error loading owner dashboard: $error');
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _changeRange(int days) async {
    if (_rangeDays == days) return;
    setState(() => _rangeDays = days);
    await _loadOverviewData();
  }

  String _money(double value) => NumberFormat.currency(
    locale: 'en_IN',
    symbol: '₹',
    decimalDigits: 0,
  ).format(value);

  Color _statusColor(String status) {
    switch (status.toLowerCase()) {
      case 'preparing':
      case 'cooking':
        return const Color(0xFFB95D2D);
      case 'ready':
        return const Color(0xFF557D91);
      case 'served':
      case 'completed':
      case 'paid':
        return const Color(0xFF548568);
      case 'cancelled':
        return const Color(0xFFD65A4A);
      default:
        return AppColors.orange;
    }
  }

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final compact = width < 700;
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(color: AppColors.orange),
      );
    }
    return RefreshIndicator(
      color: AppColors.orange,
      onRefresh: _loadOverviewData,
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.fromLTRB(
          compact ? 16 : 30,
          compact ? 18 : 28,
          compact ? 16 : 30,
          36,
        ),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1500),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _pageHeading(
                  context,
                  compact,
                ).animate().fadeIn().slideY(begin: -.04),
                const SizedBox(height: 22),
                _welcomeCard(compact),
                const SizedBox(height: 22),
                _metricGrid(width),
                const SizedBox(height: 22),
                _mainPanels(context, width),
                const SizedBox(height: 22),
                _operationsPanels(context, width),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _pageHeading(BuildContext context, bool compact) => Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Owner dashboard',
              style: TextStyle(
                color: AppColors.ink,
                fontSize: 23,
                fontWeight: FontWeight.w700,
                letterSpacing: -.6,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              DateFormat('EEEE, d MMMM yyyy').format(DateTime.now()),
              style: const TextStyle(color: AppColors.muted, fontSize: 12),
            ),
          ],
        ),
      ),
      if (!compact) ...[
        _softAction(
          Icons.calendar_today_outlined,
          DateFormat('d MMM').format(DateTime.now()),
          null,
        ),
        const SizedBox(width: 8),
      ],
      _softAction(
        Icons.refresh_rounded,
        compact ? '' : 'Refresh',
        _loadOverviewData,
      ),
    ],
  );

  Widget _softAction(IconData icon, String label, VoidCallback? action) =>
      OutlinedButton.icon(
        onPressed: action,
        icon: Icon(icon, size: 15),
        label: Text(label),
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.ink,
          side: const BorderSide(color: AppColors.line),
          backgroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(11),
          ),
        ),
      );

  Widget _welcomeCard(bool compact) => Container(
    width: double.infinity,
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
          right: compact ? -75 : 110,
          top: -105,
          child: Container(
            width: 260,
            height: 260,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: Colors.white.withValues(alpha: .06),
                width: 35,
              ),
            ),
          ),
        ),
        Padding(
          padding: EdgeInsets.all(compact ? 20 : 27),
          child: compact
              ? Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _welcomeCopy(),
                    const SizedBox(height: 18),
                    _todayBadge(),
                  ],
                )
              : Row(
                  children: [
                    Expanded(child: _welcomeCopy()),
                    _todayBadge(),
                  ],
                ),
        ),
      ],
    ),
  ).animate().fadeIn(delay: 80.ms).slideY(begin: .035);

  Widget _welcomeCopy() => Column(
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
        'Good ${DateTime.now().hour < 12
            ? 'morning'
            : DateTime.now().hour < 17
            ? 'afternoon'
            : 'evening'}, $_username',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 26,
          fontWeight: FontWeight.w700,
          letterSpacing: -.65,
        ),
      ),
      const SizedBox(height: 6),
      const Text(
        'A clear view of today’s service and performance.',
        style: TextStyle(color: Color(0xFFCFD0D0), fontSize: 12),
      ),
    ],
  );

  Widget _todayBadge() => Container(
    padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .08),
      borderRadius: BorderRadius.circular(13),
      border: Border.all(color: Colors.white.withValues(alpha: .12)),
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
  );

  Widget _metricGrid(double width) {
    final columns = width >= 1330
        ? 4
        : width >= 760
        ? 2
        : 1;
    final cards = [
      _MetricData(
        'Today’s revenue',
        _revenueVisible ? _money(_totalSales) : 'Restricted',
        Icons.currency_rupee_rounded,
        AppColors.orange,
        _averageOrder == null
            ? 'Paid orders today'
            : 'Avg. order ${_money(_averageOrder!)}',
      ),
      _MetricData(
        'Active orders',
        '$_liveOrdersCount',
        Icons.receipt_long_rounded,
        const Color(0xFFB95D2D),
        'Currently in service',
      ),
      _MetricData(
        'Table occupancy',
        '$_occupiedTablesCount / $_totalTablesCount',
        Icons.table_restaurant_rounded,
        const Color(0xFF64747D),
        'Occupied tables',
      ),
      _MetricData(
        'Low stock alerts',
        '$_lowStockCount',
        Icons.inventory_2_outlined,
        _lowStockCount > 0 ? const Color(0xFFD65A4A) : const Color(0xFF548568),
        _lowStockCount > 0 ? 'Needs attention' : 'Inventory healthy',
      ),
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final spacing = 14.0;
        final cardWidth =
            (constraints.maxWidth - spacing * (columns - 1)) / columns;
        return Wrap(
          spacing: spacing,
          runSpacing: spacing,
          children: cards
              .map(
                (item) => SizedBox(
                  width: cardWidth,
                  height: 148,
                  child: _metricCard(item),
                ),
              )
              .toList(),
        );
      },
    );
  }

  Widget _metricCard(_MetricData item) {
    var hovered = false;
    return StatefulBuilder(
      builder: (context, setCard) => MouseRegion(
        onEnter: (_) => setCard(() => hovered = true),
        onExit: (_) => setCard(() => hovered = false),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          transform: Matrix4.translationValues(0, hovered ? -3 : 0, 0),
          padding: const EdgeInsets.all(18),
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
                blurRadius: hovered ? 20 : 11,
                offset: Offset(0, hovered ? 10 : 4),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      item.title,
                      style: const TextStyle(
                        color: AppColors.muted,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: item.color.withValues(alpha: .1),
                      borderRadius: BorderRadius.circular(11),
                    ),
                    child: Icon(item.icon, color: item.color, size: 19),
                  ),
                ],
              ),
              Text(
                item.value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: AppColors.ink,
                  fontSize: 25,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -.55,
                ),
              ),
              Row(
                children: [
                  Container(
                    width: 5,
                    height: 5,
                    decoration: BoxDecoration(
                      color: item.color,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      item.subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.muted,
                        fontSize: 11,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _mainPanels(BuildContext context, double width) {
    final chart = _panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: _PanelHeading(
                  title: 'Revenue overview',
                  subtitle: 'Daily paid revenue',
                ),
              ),
              _rangeButton(7),
              const SizedBox(width: 5),
              _rangeButton(30),
            ],
          ),
          const SizedBox(height: 22),
          if (_revenueVisible && _hasTrend)
            SizedBox(
              height: 210,
              child: _RevenueChart(values: _trend, days: _rangeDays),
            )
          else
            _emptyChart(
              _revenueVisible
                  ? 'Revenue trend will appear as orders are recorded.'
                  : 'Revenue details are not available for this account.',
            ),
          const SizedBox(height: 15),
          Row(
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: const BoxDecoration(
                  color: AppColors.orange,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 7),
              const Text(
                'Revenue',
                style: TextStyle(color: AppColors.muted, fontSize: 11),
              ),
              const Spacer(),
              Text(
                _revenueVisible ? '${_rangeDays} day view' : 'Limited access',
                style: const TextStyle(color: AppColors.muted, fontSize: 11),
              ),
            ],
          ),
        ],
      ),
    );
    final side = Column(
      children: [
        _panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const _PanelHeading(
                title: 'Profit snapshot',
                subtitle: 'Cost and margin visibility',
              ),
              const SizedBox(height: 18),
              _dataUnavailable(
                Icons.account_balance_wallet_outlined,
                'Profit data isn’t connected yet',
                'Your sales are available; cost data is not returned by the current dashboard API.',
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        _panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const _PanelHeading(
                title: 'Branch performance',
                subtitle: 'Live service health',
              ),
              const SizedBox(height: 16),
              _progressLine(
                'Table occupancy',
                _totalTablesCount == 0
                    ? 0
                    : _occupiedTablesCount / _totalTablesCount,
                '$_occupiedTablesCount / $_totalTablesCount',
              ),
              const SizedBox(height: 14),
              _progressLine(
                'Orders in service',
                (_liveOrdersCount / 12).clamp(0, 1).toDouble(),
                '$_liveOrdersCount active',
              ),
              const SizedBox(height: 11),
              Text(
                'Based on the selected branch',
                style: TextStyle(color: AppColors.muted, fontSize: 10),
              ),
            ],
          ),
        ),
      ],
    );
    return width >= 1080
        ? Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(flex: 7, child: chart),
              const SizedBox(width: 16),
              Expanded(flex: 4, child: side),
            ],
          )
        : Column(children: [chart, const SizedBox(height: 16), side]);
  }

  Widget _rangeButton(int days) => ChoiceChip(
    label: Text('${days}D'),
    selected: _rangeDays == days,
    onSelected: (_) => _changeRange(days),
    labelStyle: TextStyle(
      color: _rangeDays == days ? Colors.white : AppColors.muted,
      fontSize: 10,
      fontWeight: FontWeight.w700,
    ),
    selectedColor: AppColors.orange,
    backgroundColor: AppColors.background,
    side: BorderSide.none,
    visualDensity: VisualDensity.compact,
    padding: EdgeInsets.zero,
  );

  Widget _emptyChart(String message) => Container(
    height: 210,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      color: AppColors.background.withValues(alpha: .7),
      borderRadius: BorderRadius.circular(13),
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(Icons.show_chart_rounded, color: AppColors.orange, size: 27),
        const SizedBox(height: 9),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Text(
            message,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.muted, fontSize: 12),
          ),
        ),
      ],
    ),
  );

  Widget _operationsPanels(BuildContext context, double width) {
    final ordersPanel = _panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: _PanelHeading(
                  title: 'Recent orders',
                  subtitle: 'Latest activity at this branch',
                ),
              ),
              TextButton(
                onPressed: () => context.go('/dashboard/orders'),
                child: const Text('View all'),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (_recentOrders.isEmpty)
            _dataUnavailable(
              Icons.receipt_long_outlined,
              'No recent orders',
              'New orders will appear here as they come in.',
            )
          else
            ..._recentOrders.map(_orderRow),
        ],
      ),
    );
    final attention = _panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _PanelHeading(
            title: 'Today’s attention',
            subtitle: 'Quick operational signals',
          ),
          const SizedBox(height: 17),
          _signalRow(
            Icons.inventory_2_outlined,
            'Inventory alerts',
            _lowStockCount > 0
                ? '$_lowStockCount items need review'
                : 'No low stock items',
            _lowStockCount > 0
                ? const Color(0xFFD65A4A)
                : const Color(0xFF548568),
            () => context.go('/dashboard/inventory'),
          ),
          const Divider(height: 22, color: AppColors.line),
          _signalRow(
            Icons.event_available_outlined,
            'Reservations',
            'Reservation feed not connected',
            AppColors.muted,
            () => context.go('/dashboard/tables'),
          ),
          const Divider(height: 22, color: AppColors.line),
          _signalRow(
            Icons.groups_2_outlined,
            'Team on shift',
            'Staff overview',
            const Color(0xFF64747D),
            () => context.go('/dashboard/user_management'),
          ),
          const Divider(height: 22, color: AppColors.line),
          _signalRow(
            Icons.local_fire_department_outlined,
            'Popular menu items',
            'Explore product performance',
            AppColors.orange,
            () => context.go('/dashboard/analytics'),
          ),
        ],
      ),
    );
    return width >= 1080
        ? Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(flex: 7, child: ordersPanel),
              const SizedBox(width: 16),
              Expanded(flex: 4, child: attention),
            ],
          )
        : Column(
            children: [ordersPanel, const SizedBox(height: 16), attention],
          );
  }

  Widget _orderRow(OrderModel order) {
    final statusColor = _statusColor(order.status);
    final time = DateTime.tryParse(order.orderTime);
    final when = time == null
        ? ''
        : DateFormat('h:mm a').format(time.toLocal());
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: AppColors.orangeLight,
              borderRadius: BorderRadius.circular(11),
            ),
            child: const Icon(
              Icons.receipt_long_rounded,
              color: AppColors.orange,
              size: 18,
            ),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Order #${order.id ?? '—'}',
                  style: const TextStyle(
                    color: AppColors.ink,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  '${order.type}  ·  $when',
                  style: const TextStyle(color: AppColors.muted, fontSize: 10),
                ),
              ],
            ),
          ),
          Text(
            _money(order.totalAmount),
            style: const TextStyle(
              color: AppColors.ink,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(width: 9),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
            decoration: BoxDecoration(
              color: statusColor.withValues(alpha: .1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              order.status,
              style: TextStyle(
                color: statusColor,
                fontSize: 9,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _signalRow(
    IconData icon,
    String title,
    String detail,
    Color color,
    VoidCallback onTap,
  ) => InkWell(
    onTap: onTap,
    borderRadius: BorderRadius.circular(10),
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: color.withValues(alpha: .1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, color: color, size: 17),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppColors.ink,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  detail,
                  style: const TextStyle(color: AppColors.muted, fontSize: 10),
                ),
              ],
            ),
          ),
          const Icon(
            Icons.arrow_forward_ios_rounded,
            size: 12,
            color: AppColors.muted,
          ),
        ],
      ),
    ),
  );

  Widget _progressLine(String label, double value, String trailing) => Column(
    children: [
      Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                color: AppColors.ink,
                fontSize: 11,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          Text(
            trailing,
            style: const TextStyle(color: AppColors.muted, fontSize: 10),
          ),
        ],
      ),
      const SizedBox(height: 8),
      ClipRRect(
        borderRadius: BorderRadius.circular(5),
        child: LinearProgressIndicator(
          value: value,
          minHeight: 6,
          backgroundColor: AppColors.background,
          color: AppColors.orange,
        ),
      ),
    ],
  );

  Widget _dataUnavailable(IconData icon, String title, String detail) =>
      Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 21),
        decoration: BoxDecoration(
          color: AppColors.background.withValues(alpha: .65),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: [
            Icon(icon, color: AppColors.orange, size: 24),
            const SizedBox(height: 8),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppColors.ink,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              detail,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppColors.muted,
                fontSize: 10,
                height: 1.4,
              ),
            ),
          ],
        ),
      );

  Widget _panel({required Widget child}) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(19),
    decoration: BoxDecoration(
      color: Colors.white,
      border: Border.all(color: AppColors.line),
      borderRadius: BorderRadius.circular(16),
      boxShadow: const [
        BoxShadow(
          color: Color(0x070D1117),
          blurRadius: 14,
          offset: Offset(0, 5),
        ),
      ],
    ),
    child: child,
  );
}

class _MetricData {
  const _MetricData(
    this.title,
    this.value,
    this.icon,
    this.color,
    this.subtitle,
  );
  final String title, value, subtitle;
  final IconData icon;
  final Color color;
}

class _PanelHeading extends StatelessWidget {
  const _PanelHeading({required this.title, required this.subtitle});
  final String title, subtitle;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        title,
        style: const TextStyle(
          color: AppColors.ink,
          fontSize: 14,
          fontWeight: FontWeight.w700,
          letterSpacing: -.2,
        ),
      ),
      const SizedBox(height: 4),
      Text(
        subtitle,
        style: const TextStyle(color: AppColors.muted, fontSize: 10),
      ),
    ],
  );
}

class _RevenueChart extends StatelessWidget {
  const _RevenueChart({required this.values, required this.days});
  final List<double?> values;
  final int days;
  @override
  Widget build(BuildContext context) => CustomPaint(
    painter: _RevenuePainter(values),
    child: SizedBox.expand(
      child: Align(
        alignment: Alignment.bottomCenter,
        child: Padding(
          padding: const EdgeInsets.only(bottom: 1),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: List.generate(values.length, (index) {
              final date = DateTime.now().subtract(
                Duration(days: values.length - 1 - index),
              );
              return Text(
                days <= 7
                    ? DateFormat('EEE').format(date)
                    : DateFormat('d').format(date),
                style: const TextStyle(color: AppColors.muted, fontSize: 9),
              );
            }),
          ),
        ),
      ),
    ),
  );
}

class _RevenuePainter extends CustomPainter {
  _RevenuePainter(this.values);
  final List<double?> values;
  @override
  void paint(Canvas canvas, Size size) {
    const left = 5.0, right = 5.0, top = 12.0, bottom = 28.0;
    final width = size.width - left - right,
        height = size.height - top - bottom;
    final grid = Paint()
      ..color = AppColors.line
      ..strokeWidth = 1;
    for (var i = 0; i < 4; i++) {
      final y = top + height * i / 3;
      canvas.drawLine(Offset(left, y), Offset(size.width - right, y), grid);
    }
    final available = values.whereType<double>().toList();
    if (available.isEmpty) return;
    final maxValue = available.reduce((a, b) => a > b ? a : b);
    final denominator = maxValue <= 0 ? 1.0 : maxValue;
    final points = <Offset>[];
    for (var i = 0; i < values.length; i++) {
      final value = values[i];
      if (value == null) {
        points.add(Offset(double.nan, double.nan));
        continue;
      }
      points.add(
        Offset(
          left + width * (values.length <= 1 ? .5 : i / (values.length - 1)),
          top + height * (1 - value / denominator),
        ),
      );
    }
    final fill = Path();
    var started = false;
    for (final point in points) {
      if (!point.dx.isFinite) {
        started = false;
        continue;
      }
      if (!started) {
        fill.moveTo(point.dx, top + height);
        fill.lineTo(point.dx, point.dy);
        started = true;
      } else {
        fill.lineTo(point.dx, point.dy);
      }
    }
    if (started) {
      final last = points.lastWhere((p) => p.dx.isFinite);
      fill.lineTo(last.dx, top + height);
      fill.close();
      canvas.drawPath(
        fill,
        Paint()
          ..shader = LinearGradient(
            colors: [
              AppColors.orange.withValues(alpha: .2),
              AppColors.orange.withValues(alpha: .01),
            ],
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
          ).createShader(Rect.fromLTWH(0, top, size.width, height)),
      );
    }
    final line = Paint()
      ..color = AppColors.orange
      ..strokeWidth = 2.7
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final segment = Path();
    started = false;
    for (final point in points) {
      if (!point.dx.isFinite) {
        if (segment.computeMetrics().isNotEmpty) canvas.drawPath(segment, line);
        segment.reset();
        started = false;
        continue;
      }
      if (!started) {
        segment.moveTo(point.dx, point.dy);
        started = true;
      } else {
        segment.lineTo(point.dx, point.dy);
      }
    }
    if (segment.computeMetrics().isNotEmpty) {
      canvas.drawPath(segment, line);
    }
    final dot = Paint()..color = AppColors.orange;
    for (final point in points.where((p) => p.dx.isFinite)) {
      canvas.drawCircle(point, 3.3, Paint()..color = Colors.white);
      canvas.drawCircle(point, 2.4, dot);
    }
  }

  @override
  bool shouldRepaint(covariant _RevenuePainter oldDelegate) =>
      oldDelegate.values != values;
}
