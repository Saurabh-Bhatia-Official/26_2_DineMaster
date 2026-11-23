import 'package:flutter/material.dart';
import 'package:dine_master/core/theme/app_colors.dart';
import 'package:intl/intl.dart';
import 'dart:async';
import '../services/backend_api_client.dart';
import '../core/api/api_mode.dart';

class LiveTrackingScreen extends StatefulWidget {
  const LiveTrackingScreen({super.key});

  @override
  State<LiveTrackingScreen> createState() => _LiveTrackingScreenState();
}

class _LiveTrackingScreenState extends State<LiveTrackingScreen> with SingleTickerProviderStateMixin {
  final BackendApiClient _api = const BackendApiClient();
  late TabController _tabController;
  bool _isLoading = true;
  bool _isRefreshing = false;
  bool _isLoadingAuditLogs = false;
  bool _hasLoaded = false;
  String? _loadError;
  Timer? _pollingTimer;

  // Real-time data lists
  List<Map<String, dynamic>> _liveOrders = [];
  List<Map<String, dynamic>> _auditLogs = [];
  
  // Analytics State
  double _avgPrepTime = 12.5; // fallback defaults
  int _totalCompleted = 0;
  int _totalDelayed = 0;
  List<Map<String, dynamic>> _chefStats = [];
  List<Map<String, dynamic>> _peakHours = [];


  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _tabController.addListener(_handleTabChange);
    _loadAllTrackingData();
    
    // Refresh at a lower rate; never overlap a slow API refresh.
    _pollingTimer = Timer.periodic(const Duration(seconds: 30), (timer) {
      _loadAllTrackingData(silent: true);
    });
  }

  @override
  void dispose() {
    _pollingTimer?.cancel();
    _tabController.removeListener(_handleTabChange);
    _tabController.dispose();
    super.dispose();
  }

  void _handleTabChange() {
    if (!_tabController.indexIsChanging && _tabController.index == 1) {
      _loadAuditLogs();
    }
  }

  Future<void> _loadAllTrackingData({bool silent = false}) async {
    if (_isRefreshing) return;
    _isRefreshing = true;
    if (!silent) {
      setState(() => _isLoading = true);
    }
    try {
      final restaurantId = await _selectedRestaurantId();
      final orders = await _getList('/api/v1/orders?restaurantId=$restaurantId');
      final tickets = await _getList(
        '/api/v1/kitchen-tickets?restaurantId=$restaurantId&sort=oldest',
      );
      final tables = await _getList('/api/v1/tables?restaurantId=$restaurantId');
      final tableNames = <String, String>{};
      for (final table in tables) {
        tableNames['${table['id']}'] =
            '${table['name'] ?? table['tableNumber'] ?? table['table_number'] ?? table['id']}';
      }
      final ticketsByOrder = <String, List<Map<String, dynamic>>>{};
      for (final ticket in tickets) {
        ticketsByOrder.putIfAbsent('${ticket['orderId']}', () => []).add(ticket);
      }
      orders.sort((a, b) => '${b['orderTime'] ?? ''}'.compareTo('${a['orderTime'] ?? ''}'));
      final liveOrders = orders.map((o) {
        final orderTickets = ticketsByOrder['${o['id']}'] ?? const [];
        final latestTicket = orderTickets.isEmpty ? <String, dynamic>{} : orderTickets.last;
        final tableId = o['tableId'];
        final rawType = '${o['type'] ?? ''}'.toUpperCase().replaceAll('_', '-');
        return <String, dynamic>{
          'id': _asInt(o['id']),
          'customer_name': o['customerName'] ?? 'Walk-in Guest',
          'customer_phone': o['customerPhone'] ?? 'N/A',
          'total_amount': o['totalAmount'] ?? 0,
          'status': o['status'] ?? 'Received',
          'type': rawType == 'DINE-IN' ? 'Dine-in' : 'Takeaway',
          'order_time': o['orderTime'] ?? '',
          'chef_name': latestTicket['assignedChef'] ?? 'Unassigned',
          'estimated_time': latestTicket['estimatedMinutes'] ?? 15,
          'delay_reason': latestTicket['delayReason'],
          'table_number': tableNames['$tableId'] ?? (tableId == null ? 'Takeaway' : '$tableId'),
          'kots_count': orderTickets.length,
        };
      }).toList();

      // Calculate prep metrics from REST kitchen ticket item timestamps.
      double totalPrepMinutes = 0;
      int completedCount = 0;
      Map<String, List<double>> chefTimes = {};
      for (final ticket in tickets) {
        final items = ticket['items'];
        if (items is! List) continue;
        for (final rawItem in items) {
          if (rawItem is! Map) continue;
          final item = Map<String, dynamic>.from(rawItem);
          final seconds = _asInt(item['prepSeconds']);
          if (seconds != null && seconds >= 0) {
          final diff = seconds / 60.0;
          totalPrepMinutes += diff;
          completedCount++;
          final chef = '${item['chefName'] ?? ticket['assignedChef'] ?? 'Kitchen'}';
          chefTimes.putIfAbsent(chef, () => []).add(diff);
          }
        }
      }

      // Chef Stats
      List<Map<String, dynamic>> chefStats = [];
      chefTimes.forEach((chef, times) {
        final avg = times.reduce((a, b) => a + b) / times.length;
        chefStats.add({
          'chef_name': chef,
          'orders_completed': times.length,
          'avg_time': avg.toStringAsFixed(1),
        });
      });

      // Peak Hours
      final hourCounts = <int, int>{};
      for (final order in orders) {
        final time = DateTime.tryParse('${order['orderTime'] ?? ''}');
        if (time != null) hourCounts.update(time.hour, (n) => n + 1, ifAbsent: () => 1);
      }
      final sortedHours = hourCounts.entries.toList()
        ..sort((a, b) => b.value.compareTo(a.value));
      final peakHours = sortedHours.map((entry) {
        final h = entry.key;
        final ampm = h >= 12 ? 'PM' : 'AM';
        final displayHour = h == 0 ? 12 : (h > 12 ? h - 12 : h);
        return {
          'time_range': '$displayHour:00 $ampm',
          'order_count': entry.value,
        };
      }).toList();

      final int completedOrdersCount = orders.where((o) =>
          ['COMPLETED', 'BILLED', 'PAID'].contains('${o['status']}'.toUpperCase())).length;
      final int delayedOrdersCount = liveOrders.where((o) => o['delay_reason'] != null).length;

      if (mounted) {
        setState(() {
          _liveOrders = liveOrders;
          _avgPrepTime = completedCount > 0 ? (totalPrepMinutes / completedCount) : 12.5;
          _totalCompleted = completedOrdersCount;
          _totalDelayed = delayedOrdersCount;
          _chefStats = chefStats;
          _peakHours = peakHours;
          _loadError = null;
          _hasLoaded = true;
          _isLoading = false;
        });
      }
    } catch (e) {
      print('Error loading tracking analytics: $e');
      if (mounted) {
        setState(() {
          if (!_hasLoaded) _loadError = e.toString();
          _isLoading = false;
        });
      }
    } finally {
      _isRefreshing = false;
    }
  }

  Future<void> _loadAuditLogs() async {
    if (_isLoadingAuditLogs) return;
    _isLoadingAuditLogs = true;
    try {
      final orders = await _getList(
        '/api/v1/orders?restaurantId=${await _selectedRestaurantId()}',
      );
      orders.sort((a, b) => '${b['orderTime'] ?? ''}'.compareTo('${a['orderTime'] ?? ''}'));
      final logResults = await Future.wait(orders.take(5).map((order) async {
        final orderId = _asInt(order['id']);
        if (orderId == null) return <Map<String, dynamic>>[];
        try {
          return await _getList('/api/v1/orders/$orderId/timeline');
        } catch (_) {
          return <Map<String, dynamic>>[];
        }
      }));
      final logs = logResults.expand((items) => items).map((log) => {
        ...log,
        'order_id': log['order_id'] ?? log['orderId'],
        'status': log['to_status'] ?? log['toStatus'] ?? log['status'] ?? 'Updated',
        'changed_at': log['created_at'] ?? log['createdAt'] ?? '',
        'changed_by': log['actor_id'] ?? log['actorId'] ?? 'System',
        'notes': log['reason'] ?? log['notes'] ?? '',
      }).toList()
        ..sort((a, b) => '${b['changed_at']}'.compareTo('${a['changed_at']}'));
      if (logs.length > 30) logs.removeRange(30, logs.length);
      if (mounted) setState(() => _auditLogs = logs);
    } catch (e) {
      debugPrint('Error loading order timelines: $e');
    } finally {
      _isLoadingAuditLogs = false;
    }
  }

  Future<int> _selectedRestaurantId() async {
    return await ApiMode.currentRestaurantId() ?? 1;
  }

  Future<List<Map<String, dynamic>>> _getList(String path) async {
    final response = await _api.getJson(path);
    if (response is! List) return const [];
    return response.whereType<Map>().map((item) => Map<String, dynamic>.from(item)).toList();
  }

  int? _asInt(dynamic value) => value is int ? value : int.tryParse('$value');

  // Get index for tracking progress bar
  double _getProgressValue(String status) {
    switch (status.toUpperCase()) {
      case 'RECEIVED':
      case 'PENDING':
      case 'DRAFT':
        return 0.15;
      case 'SENT TO KITCHEN':
      case 'CONFIRMED':
        return 0.35;
      case 'PREPARING':
      case 'COOKING':
        return 0.55;
      case 'READY':
        return 0.75;
      case 'SERVED':
        return 0.90;
      case 'COMPLETED':
      case 'BILLED':
      case 'PAID':
        return 1.0;
      case 'CANCELLED':
        return 0.0;
      default:
        return 0.1;
    }
  }

  Color _getStatusColor(String status) {
    switch (status.toUpperCase()) {
      case 'RECEIVED':
      case 'PENDING':
      case 'DRAFT':
        return Colors.blue;
      case 'SENT TO KITCHEN':
      case 'CONFIRMED':
        return Colors.orange;
      case 'PREPARING':
      case 'COOKING':
        return Colors.amber;
      case 'READY':
        return Colors.purple;
      case 'SERVED':
        return Colors.teal;
      case 'COMPLETED':
      case 'BILLED':
      case 'PAID':
        return Colors.green;
      case 'CANCELLED':
        return Colors.red;
      default:
        return Colors.grey;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Live Order Tracker & Performance', style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: Colors.white,
        foregroundColor: Colors.black,
        elevation: 0,
        bottom: TabBar(
          controller: _tabController,
          labelColor: AppColors.primary,
          unselectedLabelColor: Colors.grey,
          indicatorColor: AppColors.primary,
          tabs: const [
            Tab(icon: Icon(Icons.dashboard_customize), text: 'Live Status Tracker'),
            Tab(icon: Icon(Icons.analytics_outlined), text: 'Performance Analytics'),
          ],
        ),
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _loadError != null
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.cloud_off, size: 40, color: Colors.grey),
                    const SizedBox(height: 12),
                    const Text('Could not load live tracking data from the API.'),
                    const SizedBox(height: 8),
                    Text(_loadError!, textAlign: TextAlign.center),
                    const SizedBox(height: 12),
                    FilledButton(
                      onPressed: () => _loadAllTrackingData(),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
            )
          : TabBarView(
              controller: _tabController,
              children: [
                _buildLiveStatusTrackerView(),
                _buildPerformanceAnalyticsView(),
              ],
            ),
    );
  }

  // VIEW 1: Live Status Grid Tracker (Kanban-like rows)
  Widget _buildLiveStatusTrackerView() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Live Restaurant Order Flow',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              Text(
                '${_liveOrders.where((o) => !['COMPLETED', 'CANCELLED', 'BILLED', 'PAID'].contains('${o['status']}'.toUpperCase())).length} Active Orders',
                style: const TextStyle(color: Colors.blueGrey, fontWeight: FontWeight.w600),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: ListView.builder(
              itemCount: _liveOrders.length,
              itemBuilder: (context, index) {
                final order = _liveOrders[index];
                final status = order['status'] as String;
                return Card(
                  margin: const EdgeInsets.only(bottom: 12),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: BorderSide(color: Colors.grey.shade200),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(12.0),
                    child: Column(
                      children: [
                        Row(
                          children: [
                            CircleAvatar(
                              backgroundColor: AppColors.primaryLight,
                              child: Text('#${order['id']}', style: const TextStyle(fontWeight: FontWeight.bold, color: AppColors.primary)),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      Text(
                                        order['customer_name'],
                                        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                                      ),
                                      const SizedBox(width: 8),
                                      Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                        decoration: BoxDecoration(
                                          color: order['type'] == 'Dine-in' ? Colors.blue.shade50 : Colors.orange.shade50,
                                          borderRadius: BorderRadius.circular(4),
                                        ),
                                        child: Text(
                                          order['type'] == 'Dine-in' ? 'Table ${order['table_number']}' : 'Takeaway',
                                          style: TextStyle(
                                            color: order['type'] == 'Dine-in' ? Colors.blue.shade900 : Colors.orange.shade900,
                                            fontSize: 10,
                                            fontWeight: FontWeight.bold,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    'Time: ${DateFormat('hh:mm a').format(DateTime.tryParse(order['order_time']) ?? DateTime.now())} | Total: ₹${order['total_amount']}',
                                    style: const TextStyle(color: Colors.grey, fontSize: 12),
                                  ),
                                ],
                              ),
                            ),
                            
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                              decoration: BoxDecoration(
                                color: _getStatusColor(status).withValues(alpha: .1),
                                borderRadius: BorderRadius.circular(16),
                              ),
                              child: Text(
                                status,
                                style: TextStyle(
                                  color: _getStatusColor(status),
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ],
                        ),
                        
                        const Divider(height: 16),
                        
                        // Progress visualizer
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text('Kitchen Cook Progress', style: TextStyle(fontSize: 11, color: Colors.grey, fontWeight: FontWeight.bold)),
                            Text('Est Prep: ${order['estimated_time']} mins', style: const TextStyle(fontSize: 11, color: Colors.blueGrey)),
                          ],
                        ),
                        const SizedBox(height: 6),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(10),
                          child: LinearProgressIndicator(
                            value: _getProgressValue(status),
                            backgroundColor: Colors.grey.shade100,
                            valueColor: AlwaysStoppedAnimation<Color>(_getStatusColor(status)),
                            minHeight: 8,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  // VIEW 3: Performance Reports & Audit Logs Viewer
  Widget _buildPerformanceAnalyticsView() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Kitchen Performance KPI & Audit Trail',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            
            // Statistics metric row
            LayoutBuilder(builder: (context, constraints) {
              final isMobile = constraints.maxWidth < 600;
              return GridView.count(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                crossAxisCount: isMobile ? 2 : 4,
                crossAxisSpacing: 12,
                mainAxisSpacing: 12,
                childAspectRatio: 1.6,
                children: [
                  _buildMetricStat('Avg Prep Speed', '${_avgPrepTime.toStringAsFixed(1)} min', Icons.timer, Colors.blue),
                  _buildMetricStat('Completed Bills', '$_totalCompleted Orders', Icons.assignment_turned_in, Colors.green),
                  _buildMetricStat('Delayed Orders', '$_totalDelayed Bills', Icons.error_outline, Colors.red),
                  _buildMetricStat('Peak Workload Hour', _peakHours.isNotEmpty ? _peakHours.first['time_range'] : 'N/A', Icons.wb_sunny_outlined, Colors.amber),
                ],
              );
            }),

            const SizedBox(height: 24),
            
            // Peak Hours Table
            const Text('Peak Order Volume Hours', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            const SizedBox(height: 8),
            Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey.shade200),
              ),
              child: Table(
                border: TableBorder.symmetric(inside: BorderSide(color: Colors.grey.shade100)),
                children: [
                  const TableRow(
                    decoration: BoxDecoration(color: Colors.grey),
                    children: [
                      Padding(padding: EdgeInsets.all(10.0), child: Text('Time Frame', style: TextStyle(fontWeight: FontWeight.bold))),
                      Padding(padding: EdgeInsets.all(10.0), child: Text('Total Orders Logged', style: TextStyle(fontWeight: FontWeight.bold))),
                    ],
                  ),
                  if (_peakHours.isEmpty)
                    const TableRow(children: [
                      Padding(padding: EdgeInsets.all(12.0), child: Text('No order history recorded yet.')),
                      Padding(padding: EdgeInsets.all(12.0), child: Text('')),
                    ])
                  else
                    ..._peakHours.map((ph) => TableRow(
                      children: [
                        Padding(padding: const EdgeInsets.all(10.0), child: Text(ph['time_range'])),
                        Padding(padding: const EdgeInsets.all(10.0), child: Text('${ph['order_count']} orders', style: const TextStyle(fontWeight: FontWeight.bold))),
                      ],
                    )),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // Chef Performance Speeds
            const Text('Chef Preparation Speeds', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            const SizedBox(height: 8),
            Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey.shade200),
              ),
              child: Table(
                border: TableBorder.symmetric(inside: BorderSide(color: Colors.grey.shade100)),
                children: [
                  const TableRow(
                    decoration: BoxDecoration(color: Colors.grey),
                    children: [
                      Padding(padding: EdgeInsets.all(10.0), child: Text('Chef / Kitchen Unit', style: TextStyle(fontWeight: FontWeight.bold))),
                      Padding(padding: EdgeInsets.all(10.0), child: Text('Completed Tickets', style: TextStyle(fontWeight: FontWeight.bold))),
                      Padding(padding: EdgeInsets.all(10.0), child: Text('Avg Prep Speed', style: TextStyle(fontWeight: FontWeight.bold))),
                    ],
                  ),
                  if (_chefStats.isEmpty)
                    const TableRow(children: [
                      Padding(padding: EdgeInsets.all(12.0), child: Text('No completed tickets for chef performance analytics.')),
                      Padding(padding: EdgeInsets.all(12.0), child: Text('')),
                      Padding(padding: EdgeInsets.all(12.0), child: Text('')),
                    ])
                  else
                    ..._chefStats.map((cs) => TableRow(
                      children: [
                        Padding(padding: const EdgeInsets.all(10.0), child: Text(cs['chef_name'])),
                        Padding(padding: const EdgeInsets.all(10.0), child: Text('${cs['orders_completed']}')),
                        Padding(padding: const EdgeInsets.all(10.0), child: Text('${cs['avg_time']} mins', style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.bold))),
                      ],
                    )),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // Timeline Audit Log List
            const Text('Live Timeline logs Audit Trail', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey.shade200),
              ),
              child: ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: _auditLogs.length,
                separatorBuilder: (context, index) => const Divider(height: 12),
                itemBuilder: (context, index) {
                  final log = _auditLogs[index];
                  final parsedTime = DateTime.tryParse(log['changed_at'] as String) ?? DateTime.now();

                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.history_toggle_off, color: Colors.blueGrey.shade300, size: 16),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                Text(
                                  'Order #${log['order_id']} → Status: ${log['status']}',
                                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
                                ),
                                Text(
                                  DateFormat('hh:mm a').format(parsedTime),
                                  style: const TextStyle(fontSize: 10, color: Colors.grey),
                                ),
                              ],
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '${log['notes'] ?? ''} [By: ${log['changed_by'] ?? 'System'}]',
                              style: TextStyle(fontSize: 11, color: Colors.grey.shade700),
                            ),
                          ],
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetricStat(String label, String val, IconData icon, Color color) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.grey.shade200),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(val, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              Icon(icon, color: color, size: 20),
            ],
          ),
          const Spacer(),
          Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey, fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }
}
