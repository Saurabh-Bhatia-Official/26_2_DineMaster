import 'package:flutter/material.dart';
import '../core/api/api_mode.dart';
import '../core/database/database_helper.dart';
import '../services/backend_api_client.dart';

class CustomersScreen extends StatefulWidget {
  const CustomersScreen({super.key});

  @override
  State<CustomersScreen> createState() => _CustomersScreenState();
}

class _CustomersScreenState extends State<CustomersScreen> {
  List<Map<String, dynamic>> _customers = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      List<Map<String, dynamic>> rows;
      if (await ApiMode.useBackendApi()) {
        final response = await const BackendApiClient().getJson('/api/v1/customers');
        if (response is! List) throw const FormatException('Invalid customer response');
        rows = response.whereType<Map>().map((row) => Map<String, dynamic>.from(row)).toList();
      } else {
        rows = await (await DatabaseHelper.instance.database).query('customers', orderBy: 'name ASC');
      }
      if (mounted) setState(() { _customers = rows; _loading = false; });
    } catch (e) {
      if (mounted) {
        setState(() => _loading = false);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Could not load customers: $e')));
      }
    }
  }

  Future<void> _editCustomer([Map<String, dynamic>? customer]) async {
    final name = TextEditingController(text: customer?['name']?.toString() ?? '');
    final phone = TextEditingController(text: customer?['phone']?.toString() ?? '');
    final email = TextEditingController(text: customer?['email']?.toString() ?? '');
    final formKey = GlobalKey<FormState>();
    final saved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(customer == null ? 'Add customer' : 'Edit customer'),
        content: Form(
          key: formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(controller: name, decoration: const InputDecoration(labelText: 'Name'), validator: (v) => v == null || v.trim().isEmpty ? 'Name is required' : null),
              TextFormField(controller: phone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Phone'), validator: (v) => v == null || v.trim().isEmpty ? 'Phone is required' : null),
              TextFormField(controller: email, keyboardType: TextInputType.emailAddress, decoration: const InputDecoration(labelText: 'Email (optional)'),
                validator: (v) => v != null && v.trim().isNotEmpty && !v.contains('@') ? 'Enter a valid email' : null),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Cancel')),
          ElevatedButton(onPressed: () {
            if (formKey.currentState!.validate()) Navigator.pop(dialogContext, true);
          }, child: const Text('Save')),
        ],
      ),
    );
    if (saved != true) return;
    try {
      final body = <String, dynamic>{
        'name': name.text.trim(),
        'phone': phone.text.trim(),
        'email': email.text.trim().isEmpty ? null : email.text.trim(),
      };
      if (await ApiMode.useBackendApi()) {
        const api = BackendApiClient();
        if (customer == null) {
          await api.postJson('/api/v1/customers', body);
        } else {
          await api.putJson('/api/v1/customers/${customer['id']}', body);
        }
      } else {
        final db = await DatabaseHelper.instance.database;
        if (customer == null) {
          await db.insert('customers', {
            ...body,
            'created_at': DateTime.now().toIso8601String(),
          });
        } else {
          await db.update('customers', body, where: 'id = ?', whereArgs: [customer['id']]);
        }
      }
      await _load();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Could not save customer: $e')));
    }
  }

  Future<void> _deleteCustomer(Map<String, dynamic> customer) async {
    try {
      if (await ApiMode.useBackendApi()) {
        await const BackendApiClient().delete('/api/v1/customers/${customer['id']}');
      } else {
        await (await DatabaseHelper.instance.database).delete('customers', where: 'id = ?', whereArgs: [customer['id']]);
      }
      await _load();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Could not delete customer: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Customers'), actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))]),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _customers.isEmpty
              ? const Center(child: Text('No customers yet'))
              : ListView.builder(
                  itemCount: _customers.length,
                  itemBuilder: (context, index) {
                    final customer = _customers[index];
                    return ListTile(
                      leading: const CircleAvatar(child: Icon(Icons.person_outline)),
                      title: Text(customer['name']?.toString() ?? ''),
                      subtitle: Text('${customer['phone'] ?? ''}${customer['email'] == null ? '' : ' · ${customer['email']}'}'),
                      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                        IconButton(icon: const Icon(Icons.edit_outlined), onPressed: () => _editCustomer(customer)),
                        IconButton(icon: const Icon(Icons.delete_outline, color: Colors.red), onPressed: () => _deleteCustomer(customer)),
                      ]),
                    );
                  },
                ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _editCustomer(),
        icon: const Icon(Icons.person_add_alt_1),
        label: const Text('Add customer'),
      ),
    );
  }
}

