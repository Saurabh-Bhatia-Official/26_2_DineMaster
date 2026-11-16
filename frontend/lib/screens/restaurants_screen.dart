
import 'dart:async';

import 'package:flutter/material.dart';

import '../core/api/api_mode.dart';
import '../core/theme/app_colors.dart';
import '../repositories/restaurant_repository.dart';
import '../services/backend_api_client.dart';
import '../services/sync_service.dart';

class RestaurantsScreen extends StatefulWidget {
  const RestaurantsScreen({super.key});

  @override
  State<RestaurantsScreen> createState() => _RestaurantsScreenState();
}

class _RestaurantsScreenState extends State<RestaurantsScreen> {
  final RestaurantRepository _repo = RestaurantRepository();

  List<Map<String, dynamic>> _restaurants = [];
  bool _isLoading = true;
  StreamSubscription<String>? _syncSubscription;

  @override
  void initState() {
    super.initState();
    _loadRestaurants();

    // Keep the list in sync when other screens modify restaurant data.
    _syncSubscription = SyncService.instance.syncEvents.listen((event) {
      if (mounted && event.contains('restaurant')) {
        _loadRestaurants();
      }
    });
  }

  @override
  void dispose() {
    _syncSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadRestaurants() async {
    if (!mounted) return;

    setState(() => _isLoading = true);

    try {
      final list = await _repo.getRestaurants();

      if (!mounted) return;

      setState(() {
        _restaurants = list;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;

      setState(() => _isLoading = false);

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to load restaurants: $e')),
      );
    }
  }

  void _showAddRestaurantDialog() {
    final codeController = TextEditingController();
    final nameController = TextEditingController();
    final addressController = TextEditingController();
    final phoneController = TextEditingController();
    final timezoneController = TextEditingController(text: 'Asia/Kolkata');

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Add New Restaurant'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: codeController,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: 'Branch Code',
                hintText: 'e.g. MG-ROAD',
              ),
            ),
            TextField(
              controller: nameController,
              decoration: const InputDecoration(
                labelText: 'Restaurant Name',
              ),
            ),
            TextField(
              controller: addressController,
              decoration: const InputDecoration(labelText: 'Address'),
            ),
            TextField(
              controller: phoneController,
              decoration: const InputDecoration(labelText: 'Phone'),
            ),
            TextField(
              controller: timezoneController,
              decoration: const InputDecoration(
                labelText: 'Timezone',
                hintText: 'Asia/Kolkata',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () async {
              final requiresBranchDetails = await ApiMode.useBackendApi();
              if (nameController.text.trim().isNotEmpty &&
                  (!requiresBranchDetails ||
                      (codeController.text.trim().isNotEmpty &&
                          timezoneController.text.trim().isNotEmpty))) {
                try {
                  await _repo.createRestaurant(
                    code: codeController.text.trim().toUpperCase(),
                    name: nameController.text.trim(),
                    timezone: timezoneController.text.trim(),
                    address: addressController.text.trim(),
                    phone: phoneController.text.trim(),
                  );

                  if (context.mounted) Navigator.pop(context);
                  _loadRestaurants();
                } catch (e) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('Error adding restaurant: $e')),
                    );
                  }
                }
              }
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  void _showEditRestaurantDialog(Map<String, dynamic> restaurant) {
    final codeController = TextEditingController(
      text: restaurant['code']?.toString() ?? '',
    );
    final nameController =
        TextEditingController(text: restaurant['name']?.toString() ?? '');
    final addressController =
        TextEditingController(text: restaurant['address']?.toString() ?? '');
    final phoneController =
        TextEditingController(text: restaurant['phone']?.toString() ?? '');
    final timezoneController = TextEditingController(
      text: restaurant['timezone']?.toString() ?? 'Asia/Kolkata',
    );

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Edit Restaurant'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: codeController,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(labelText: 'Branch Code'),
            ),
            TextField(
              controller: nameController,
              decoration: const InputDecoration(
                labelText: 'Restaurant Name',
              ),
            ),
            TextField(
              controller: addressController,
              decoration: const InputDecoration(labelText: 'Address'),
            ),
            TextField(
              controller: phoneController,
              decoration: const InputDecoration(labelText: 'Phone'),
            ),
            TextField(
              controller: timezoneController,
              decoration: const InputDecoration(
                labelText: 'Timezone',
                hintText: 'Asia/Kolkata',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () async {
              final requiresBranchDetails = await ApiMode.useBackendApi();
              if (nameController.text.trim().isNotEmpty &&
                  (!requiresBranchDetails ||
                      (codeController.text.trim().isNotEmpty &&
                          timezoneController.text.trim().isNotEmpty))) {
                try {
                  await _repo.updateRestaurant(
                    id: (restaurant['id'] as num).toInt(),
                    code: codeController.text.trim().toUpperCase(),
                    name: nameController.text.trim(),
                    timezone: timezoneController.text.trim(),
                    address: addressController.text.trim(),
                    phone: phoneController.text.trim(),
                  );

                  if (context.mounted) Navigator.pop(context);
                  _loadRestaurants();
                } catch (e) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('Error updating restaurant: $e')),
                    );
                  }
                }
              }
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  Future<void> _showBranchHours(Map<String, dynamic> branch) async {
    final id = (branch['id'] as num).toInt();
    const weekdays = [
      'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
      'FRIDAY', 'SATURDAY', 'SUNDAY',
    ];
    final schedule = <Map<String, dynamic>>[];
    try {
      final raw = await const BackendApiClient().getJson(
        '/api/v1/branches/$id/hours',
      );
      if (raw is List) {
        for (final item in raw.whereType<Map>()) {
          final row = Map<String, dynamic>.from(item);
          schedule.add({
            'weekday': row['weekday']?.toString().toUpperCase(),
            'openTime': row['openTime']?.toString() ?? '09:00:00',
            'closeTime': row['closeTime']?.toString() ?? '22:00:00',
            'closed': row['closed'] == true,
          });
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not load branch hours: $e')),
        );
      }
      return;
    }
    for (final day in weekdays) {
      if (!schedule.any((row) => row['weekday'] == day)) {
        schedule.add({
          'weekday': day,
          'openTime': '09:00:00',
          'closeTime': '22:00:00',
          'closed': true,
        });
      }
    }
    schedule.sort((a, b) =>
        weekdays.indexOf(a['weekday']).compareTo(weekdays.indexOf(b['weekday'])));

    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text('${branch['name']} opening hours'),
          content: SizedBox(
            width: 520,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: schedule.map((row) {
                  final day = row['weekday'] as String;
                  return Row(
                    children: [
                      SizedBox(width: 105, child: Text(day.substring(0, 3))),
                      Checkbox(
                        value: row['closed'] as bool,
                        onChanged: (value) => setDialogState(() {
                          row['closed'] = value ?? false;
                        }),
                      ),
                      const Text('Closed'),
                      if (!(row['closed'] as bool)) ...[
                        const SizedBox(width: 8),
                        Expanded(
                          child: TextFormField(
                            initialValue: (row['openTime'] as String).substring(0, 5),
                            decoration: const InputDecoration(labelText: 'Open', hintText: '09:00'),
                            onChanged: (value) => row['openTime'] = '$value:00',
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: TextFormField(
                            initialValue: (row['closeTime'] as String).substring(0, 5),
                            decoration: const InputDecoration(labelText: 'Close', hintText: '22:00'),
                            onChanged: (value) => row['closeTime'] = '$value:00',
                          ),
                        ),
                      ],
                    ],
                  );
                }).toList(),
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () async {
                try {
                  await const BackendApiClient().putJsonList(
                    '/api/v1/branches/$id/hours',
                    schedule,
                  );
                } catch (e) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('Could not save branch hours: $e')),
                    );
                  }
                  return;
                }
                if (dialogContext.mounted) Navigator.pop(dialogContext);
              },
              child: const Text('Save hours'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _deleteRestaurant(int id) async {
    // Count linked records before deleting.
    try {
      final counts = await _repo.getLinkedRecordCounts(id);

      final totalLinked = (counts['tables'] ?? 0) +
          (counts['products'] ?? 0) +
          (counts['orders'] ?? 0);

      if (totalLinked > 0 && mounted) {
        final parts = <String>[];

        if ((counts['tables'] ?? 0) > 0) {
          parts.add('${counts['tables']} table(s)');
        }
        if ((counts['products'] ?? 0) > 0) {
          parts.add('${counts['products']} product(s)');
        }
        if ((counts['orders'] ?? 0) > 0) {
          parts.add('${counts['orders']} order(s)');
        }

        final proceed = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('Warning: Linked Data Found'),
            content: Text(
              'This restaurant has ${parts.join(', ')} linked to it. '
              'Deleting it may leave orphaned data. Continue?',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Cancel'),
              ),
              ElevatedButton(
                onPressed: () => Navigator.pop(ctx, true),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.red,
                  foregroundColor: Colors.white,
                ),
                child: const Text('Delete Anyway'),
              ),
            ],
          ),
        );

        if (proceed != true) return;
      }

      await _repo.deleteRestaurant(id);
      _loadRestaurants();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error deleting restaurant: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Restaurant Management'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loadRestaurants,
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _restaurants.isEmpty
              ? const Center(child: Text('No restaurants found'))
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: _restaurants.length,
                  itemBuilder: (context, index) {
                    final restaurant = _restaurants[index];

                    return Card(
                      elevation: 0,
                      color: Colors.white,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                        side: BorderSide(color: Colors.grey.shade200),
                      ),
                      margin: const EdgeInsets.only(bottom: 12),
                      child: ListTile(
                        leading: CircleAvatar(
                          backgroundColor: AppColors.primaryLight,
                          child: const Icon(
                            Icons.restaurant,
                            color: AppColors.primary,
                          ),
                        ),
                        title: Text(
                          restaurant['name']?.toString() ?? '',
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        subtitle: Text(
                          restaurant['address']?.toString() ?? 'No address',
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              tooltip: 'Opening hours',
                              icon: const Icon(Icons.schedule, color: Colors.indigo),
                              onPressed: () => _showBranchHours(restaurant),
                            ),
                            IconButton(
                              icon: const Icon(
                                Icons.edit,
                                color: Colors.grey,
                              ),
                              onPressed: () =>
                                  _showEditRestaurantDialog(restaurant),
                            ),
                            IconButton(
                              icon: const Icon(
                                Icons.delete,
                                color: Colors.red,
                              ),
                              onPressed: () {
                                showDialog(
                                  context: context,
                                  builder: (context) => AlertDialog(
                                    title: const Text('Delete Restaurant'),
                                    content: const Text(
                                      'Are you sure you want to delete this '
                                      'restaurant? This might affect linked data.',
                                    ),
                                    actions: [
                                      TextButton(
                                        onPressed: () =>
                                            Navigator.pop(context),
                                        child: const Text('Cancel'),
                                      ),
                                      ElevatedButton(
                                        onPressed: () {
                                          Navigator.pop(context);
                                          _deleteRestaurant(
                                            (restaurant['id'] as num).toInt(),
                                          );
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
                              },
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
      floatingActionButton: FloatingActionButton(
        onPressed: _showAddRestaurantDialog,
        backgroundColor: AppColors.primary,
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }
}
