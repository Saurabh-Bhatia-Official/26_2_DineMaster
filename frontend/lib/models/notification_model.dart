class NotificationModel {
  final int id;
  final int? branchId;
  final String type;
  final String title;
  final String body;
  final String? refType;
  final int? refId;
  final DateTime? readAt;
  final DateTime? createdAt;

  const NotificationModel({
    required this.id,
    this.branchId,
    required this.type,
    required this.title,
    required this.body,
    this.refType,
    this.refId,
    this.readAt,
    this.createdAt,
  });

  bool get isRead => readAt != null;

  factory NotificationModel.fromJson(Map<String, dynamic> json) {
    DateTime? parseDate(dynamic value) => value == null
        ? null
        : DateTime.tryParse(value.toString());

    return NotificationModel(
      id: (json['id'] as num).toInt(),
      branchId: (json['branchId'] as num?)?.toInt(),
      type: (json['type'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      body: (json['body'] ?? '').toString(),
      refType: json['refType']?.toString(),
      refId: (json['refId'] as num?)?.toInt(),
      readAt: parseDate(json['readAt']),
      createdAt: parseDate(json['createdAt']),
    );
  }
}
