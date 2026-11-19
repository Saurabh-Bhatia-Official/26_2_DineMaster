import 'package:flutter/material.dart';
import '../services/backend_api_client.dart';

class GuestMenuScreen extends StatefulWidget {
  final String token;

  const GuestMenuScreen({super.key, required this.token});

  @override
  State<GuestMenuScreen> createState() => _GuestMenuScreenState();
}

class _GuestMenuScreenState extends State<GuestMenuScreen> {
  bool _loading = true;
  String? _error;
  String _tableLabel = '';
  List<Map<String, dynamic>> _items = [];

  @override
  void initState() {
    super.initState();
    _loadMenu();
  }

  Future<void> _loadMenu() async {
    if (widget.token.isEmpty) {
      setState(() {
        _loading = false;
        _error = 'This QR code is missing its table token.';
      });
      return;
    }
    try {
      final response = await const BackendApiClient().getJson(
        '/guest/menu?t=${Uri.encodeQueryComponent(widget.token)}',
      );
      if (response is! Map || response['items'] is! List) {
        throw const FormatException('Unexpected guest menu response');
      }
      if (!mounted) return;
      setState(() {
        _tableLabel = response['tableLabel']?.toString() ?? '';
        _items = (response['items'] as List)
            .whereType<Map>()
            .map((item) => Map<String, dynamic>.from(item))
            .toList();
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = 'Could not load the menu. Please try again.';
        _loading = false;
      });
      debugPrint('Guest menu request failed: $error');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF7F7F8),
      appBar: AppBar(
        title: const Text('Menu'),
        centerTitle: true,
        backgroundColor: Colors.white,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
          ? _message(
              icon: Icons.error_outline,
              text: _error!,
              action: TextButton(onPressed: _loadMenu, child: const Text('Retry')),
            )
          : _items.isEmpty
          ? _message(
              icon: Icons.restaurant_menu,
              text: 'Menu items are not available right now.',
            )
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (_tableLabel.isNotEmpty) ...[
                  Text(
                    'Table $_tableLabel',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 12),
                ],
                ..._items.map(_menuItem),
              ],
            ),
    );
  }

  Widget _menuItem(Map<String, dynamic> item) {
    final name = item['name']?.toString() ?? 'Menu item';
    final description = item['description']?.toString();
    final category = item['category']?.toString();
    final price = (item['price'] as num?)?.toDouble() ?? 0;
    return Card(
      color: Colors.white,
      margin: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        title: Text(name, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (category != null && category.isNotEmpty) Text(category),
            if (description != null && description.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(description),
              ),
          ],
        ),
        trailing: Text(
          '₹${price.toStringAsFixed(2)}',
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
      ),
    );
  }

  Widget _message({required IconData icon, required String text, Widget? action}) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: Colors.grey),
            const SizedBox(height: 12),
            Text(text, textAlign: TextAlign.center),
            if (action != null) action,
          ],
        ),
      ),
    );
  }
}
