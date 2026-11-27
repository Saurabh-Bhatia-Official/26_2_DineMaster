import 'dart:math';

import '../models/order_model.dart';
import '../services/backend_api_client.dart';

class OrderApiRepository {
  final BackendApiClient _apiClient;

  const OrderApiRepository({
    BackendApiClient apiClient = const BackendApiClient(),
  }) : _apiClient = apiClient;

  static String newIdempotencyKey() {
    final random = Random.secure();
    final suffix = List.generate(
      24,
      (_) => random.nextInt(16).toRadixString(16),
    ).join();
    return '${DateTime.now().microsecondsSinceEpoch}-$suffix';
  }

  Future<OrderModel> createOrder({
    required String idempotencyKey,
    required int restaurantId,
    required List<OrderItemModel> items,
    int? tableId,
    int? customerId,
    int? waiterId,
    String? type,
    String? notes,
    int? guestCount,
  }) async {
    final response = await _apiClient.postJson('/api/v1/orders', {
      'restaurantId': restaurantId,
      if (tableId != null) 'tableId': tableId,
      if (customerId != null) 'customerId': customerId,
      if (waiterId != null) 'waiterId': waiterId,
      if (type != null) 'type': type,
      if (notes != null) 'notes': notes,
      if (guestCount != null) 'guestCount': guestCount,
      'items': items
          .map(
            (item) => {
              'productId': item.productId,
              'quantity': item.quantity,
              if (item.notes != null) 'notes': item.notes,
            },
          )
          .toList(),
    }, headers: {'Idempotency-Key': idempotencyKey});
    final data = Map<String, dynamic>.from(response as Map);
    // CartResponse calls this orderId; normalize it for OrderModel.
    data['id'] ??= data['orderId'];
    return OrderModel.fromMap(data);
  }

  Future<OrderModel> confirmOrder(
    int orderId, {
    required String idempotencyKey,
  }) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/confirm',
      {},
      headers: {'Idempotency-Key': idempotencyKey},
    );
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<List<OrderModel>> getOrders(int restaurantId) async {
    final response = await _apiClient.getJson(
      '/api/v1/orders?restaurantId=$restaurantId',
    );
    return _parseOrders(response);
  }

  Future<Map<String, dynamic>> addRound(
    int orderId,
    List<OrderItemModel> items,
  ) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/rounds',
      {
        'items': items
            .map((item) => {
                  'productId': item.productId,
                  'quantity': item.quantity,
                  if (item.notes != null) 'notes': item.notes,
                })
            .toList(),
      },
    );
    if (response is! Map) {
      throw const FormatException('Expected the updated order round');
    }
    return Map<String, dynamic>.from(response);
  }

  Future<void> applyDiscount(
    int orderId, {
    required String type,
    required double value,
    required String remark,
    String? managerUsername,
    String? managerPin,
  }) async {
    await _apiClient.postJson(
      '/api/v1/orders/$orderId/discount',
      {
        'type': type.toUpperCase() == 'PERCENTAGE' ? 'PERCENT' : type.toUpperCase(),
        'value': value,
        'remark': remark,
        if (managerUsername != null && managerUsername.isNotEmpty)
          'managerUsername': managerUsername,
        if (managerPin != null && managerPin.isNotEmpty)
          'managerPin': managerPin,
      },
    );
  }

  Future<OrderModel> getOrder(int id) async {
    final response = await _apiClient.getJson('/api/v1/orders/$id');
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<List<OrderItemModel>> getOrderItems(int orderId) async {
    final response = await _apiClient.getJson('/api/v1/orders/$orderId/items');
    if (response is! List) {
      throw const FormatException(
        'Expected an order item list from the backend',
      );
    }
    return response
        .map(
          (item) =>
              OrderItemModel.fromMap(Map<String, dynamic>.from(item as Map)),
        )
        .toList();
  }

  Future<OrderModel> hold(int orderId) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/hold',
      {},
    );
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<void> park(int orderId) async {
    await _apiClient.postJson('/api/v1/orders/$orderId/park', const {});
  }

  Future<void> reopenParked(int orderId) async {
    await _apiClient.postJson('/api/v1/orders/$orderId/reopen', const {});
  }

  Future<List<Map<String, dynamic>>> getParkedOrders(int restaurantId) async {
    final response = await _apiClient.getJson(
      '/api/v1/orders/parked?restaurantId=$restaurantId',
    );
    if (response is! List) {
      throw const FormatException('Expected a parked order list');
    }
    return response.map((item) {
      if (item is! Map) {
        throw const FormatException('Invalid parked order from backend');
      }
      final cart = Map<String, dynamic>.from(item);
      return {
        ...cart,
        'id': cart['orderId'] ?? cart['id'],
        'table_id': cart['tableId'] ?? cart['table_id'],
        'customer_id': cart['customerId'] ?? cart['customer_id'],
        'total_amount': cart['totalAmount'] ?? cart['total_amount'] ?? 0,
        'order_time': cart['orderTime'] ?? cart['order_time'] ?? '',
        'customer_name': cart['customerName'] ?? cart['customer_name'],
      };
    }).toList();
  }

  Future<List<Map<String, dynamic>>> getOrderTimeline(int orderId) async {
    final response = await _apiClient.getJson(
      '/api/v1/orders/$orderId/timeline',
    );
    if (response is! List) {
      throw const FormatException('Expected an order timeline');
    }
    return response.map((item) {
      final event = Map<String, dynamic>.from(item as Map);
      return {
        ...event,
        'status': event['to_status'] ?? event['toStatus'] ?? 'Updated',
        'changed_at': event['created_at'] ?? event['createdAt'],
        'changed_by': event['actor_id'] == null
            ? 'System'
            : 'User ${event['actor_id']}',
        'notes': event['reason'],
      };
    }).toList();
  }

  Future<List<Map<String, dynamic>>> getOrderKots(int orderId) async {
    final response = await _apiClient.getJson('/api/v1/orders/$orderId/kots');
    if (response is! List) {
      throw const FormatException('Expected KOT history');
    }
    return response.map((item) {
      final kot = Map<String, dynamic>.from(item as Map);
      final rawItems = kot['items'];
      final items = rawItems is List
          ? rawItems.map((rawItem) {
              final value = Map<String, dynamic>.from(rawItem as Map);
              return {
                ...value,
                'product_name': value['itemName'] ?? value['productName'] ?? 'Item',
                'prep_time': 5,
                'cook_time': 10,
              };
            }).toList()
          : <Map<String, dynamic>>[];
      return {
        ...kot,
        'kot_number': kot['kotNumber'] ?? kot['kot_number'],
        'created_at': kot['createdAt'] ?? kot['created_at'],
        'status': kot['status'] ?? kot['ticketKind'] ?? 'Pending',
        'chef_name': kot['chefName'] ?? kot['chef_name'],
        'estimated_time': kot['estimatedTime'] ?? kot['estimated_time'] ?? 15,
        'delay_reason': kot['delayReason'] ?? kot['delay_reason'],
        'items': items,
      };
    }).toList();
  }

  Future<OrderModel> cancel(int orderId, {required String reason}) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/cancel',
      {'reason': reason},
    );
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<OrderModel> recordPayment(int orderId, String paymentMethod) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/payment',
      {'paymentMethod': paymentMethod},
    );
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<Map<String, dynamic>> generateBill(int orderId) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/bill',
      const <String, dynamic>{},
    );
    if (response is! Map) {
      throw const FormatException('Expected invoice details from the backend');
    }
    return Map<String, dynamic>.from(response);
  }

  Future<Map<String, dynamic>> getBill(int orderId) async {
    final response = await _apiClient.getJson('/api/v1/orders/$orderId/bill');
    if (response is! Map) {
      throw const FormatException('Expected invoice details from the backend');
    }
    return Map<String, dynamic>.from(response);
  }

  List<OrderModel> _parseOrders(dynamic response) {
    if (response is! List) {
      throw const FormatException('Expected an order list from the backend');
    }
    return response
        .map(
          (item) => OrderModel.fromMap(Map<String, dynamic>.from(item as Map)),
        )
        .toList();
  }
}
