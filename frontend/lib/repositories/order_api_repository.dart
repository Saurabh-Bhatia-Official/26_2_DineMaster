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
    double discountAmount = 0,
  }) async {
    final response = await _apiClient.postJson('/api/v1/orders', {
      'restaurantId': restaurantId,
      if (tableId != null) 'tableId': tableId,
      if (customerId != null) 'customerId': customerId,
      if (waiterId != null) 'waiterId': waiterId,
      if (type != null) 'type': type,
      if (notes != null) 'notes': notes,
      if (guestCount != null) 'guestCount': guestCount,
      'discountAmount': discountAmount,
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

  Future<OrderModel> updateStatus(int orderId, String status) async {
    final response = await _apiClient.patchJson(
      '/api/v1/orders/$orderId/status',
      {'status': status},
    );
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<OrderModel> hold(int orderId) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/hold',
      {},
    );
    return OrderModel.fromMap(Map<String, dynamic>.from(response as Map));
  }

  Future<OrderModel> cancel(int orderId) async {
    final response = await _apiClient.postJson(
      '/api/v1/orders/$orderId/cancel',
      {},
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
