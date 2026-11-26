import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../core/theme/app_colors.dart';
import '../models/notification_model.dart';
import '../repositories/notification_repository.dart';

class NotificationInboxSheet extends StatefulWidget {
  final ValueChanged<int>? onUnreadCountChanged;

  const NotificationInboxSheet({super.key, this.onUnreadCountChanged});

  @override
  State<NotificationInboxSheet> createState() =>
      _NotificationInboxSheetState();
}

class _NotificationInboxSheetState extends State<NotificationInboxSheet> {
  final _repository = NotificationRepository();
  List<NotificationModel> _notifications = const [];
  bool _loading = true;
  bool _markingAllRead = false;
  final Set<int> _markingRead = {};
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadNotifications();
  }

  Future<void> _loadNotifications() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final notifications = await _repository.getNotifications(limit: 100);
      if (!mounted) return;
      setState(() {
        _notifications = notifications;
        _loading = false;
      });
      _notifyUnreadCount();
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not load notifications. Check your connection and retry.';
      });
    }
  }

  Future<void> _markRead(NotificationModel notification) async {
    if (notification.isRead || _markingRead.contains(notification.id)) return;
    setState(() => _markingRead.add(notification.id));
    try {
      await _repository.markRead(notification.id);
      if (!mounted) return;
      setState(() {
        _notifications = _notifications
            .map((item) => item.id == notification.id
                ? NotificationModel(
                    id: item.id,
                    branchId: item.branchId,
                    type: item.type,
                    title: item.title,
                    body: item.body,
                    refType: item.refType,
                    refId: item.refId,
                    readAt: DateTime.now(),
                    createdAt: item.createdAt,
                  )
                : item)
            .toList();
        _markingRead.remove(notification.id);
      });
      _notifyUnreadCount();
    } catch (_) {
      if (!mounted) return;
      setState(() => _markingRead.remove(notification.id));
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not mark notification as read.')),
      );
    }
  }

  Future<void> _markAllRead() async {
    if (_markingAllRead || _unreadCount == 0) return;
    setState(() => _markingAllRead = true);
    try {
      await _repository.markAllRead();
      if (!mounted) return;
      final now = DateTime.now();
      setState(() {
        _notifications = _notifications
            .map((item) => item.isRead
                ? item
                : NotificationModel(
                    id: item.id,
                    branchId: item.branchId,
                    type: item.type,
                    title: item.title,
                    body: item.body,
                    refType: item.refType,
                    refId: item.refId,
                    readAt: now,
                    createdAt: item.createdAt,
                  ))
            .toList();
        _markingAllRead = false;
      });
      _notifyUnreadCount();
    } catch (_) {
      if (!mounted) return;
      setState(() => _markingAllRead = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not mark notifications as read.')),
      );
    }
  }

  int get _unreadCount => _notifications.where((item) => !item.isRead).length;

  void _notifyUnreadCount() {
    widget.onUnreadCountChanged?.call(_unreadCount);
  }

  @override
  Widget build(BuildContext context) {
    final height = MediaQuery.sizeOf(context).height * .78;
    return Container(
      height: height,
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
      child: Column(
        children: [
          const SizedBox(height: 10),
          Container(
            width: 38,
            height: 4,
            decoration: BoxDecoration(
              color: AppColors.line,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 18, 12, 12),
            child: Row(
              children: [
                const Expanded(
                  child: Text(
                    'Notifications',
                    style: TextStyle(
                      color: AppColors.ink,
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                if (_unreadCount > 0)
                  TextButton(
                    onPressed: _markingAllRead ? null : _markAllRead,
                    child: _markingAllRead
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Mark all read'),
                  ),
                IconButton(
                  tooltip: 'Close notifications',
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(child: _buildContent()),
        ],
      ),
    );
  }

  Widget _buildContent() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: _loadNotifications,
                icon: const Icon(Icons.refresh),
                label: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }
    if (_notifications.isEmpty) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.notifications_none_rounded, size: 42, color: AppColors.muted),
            SizedBox(height: 10),
            Text('You\'re all caught up.', style: TextStyle(color: AppColors.muted)),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _loadNotifications,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: _notifications.length,
        separatorBuilder: (_, __) => const Divider(height: 1, indent: 72),
        itemBuilder: (context, index) {
          final notification = _notifications[index];
          final isMarking = _markingRead.contains(notification.id);
          return ListTile(
            onTap: notification.isRead ? null : () => _markRead(notification),
            leading: CircleAvatar(
              backgroundColor: notification.isRead
                  ? const Color(0xFFF1F2F3)
                  : AppColors.orangeLight,
              child: Icon(
                _iconFor(notification.type),
                color: notification.isRead ? AppColors.muted : AppColors.orange,
                size: 20,
              ),
            ),
            title: Text(
              notification.title,
              style: TextStyle(
                color: AppColors.ink,
                fontSize: 13,
                fontWeight: notification.isRead ? FontWeight.w500 : FontWeight.w700,
              ),
            ),
            subtitle: Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(notification.body),
                  if (notification.createdAt != null) ...[
                    const SizedBox(height: 5),
                    Text(
                      DateFormat('MMM d, h:mm a').format(notification.createdAt!.toLocal()),
                      style: const TextStyle(fontSize: 11, color: AppColors.muted),
                    ),
                  ],
                ],
              ),
            ),
            trailing: isMarking
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : notification.isRead
                    ? const Icon(Icons.check, color: AppColors.muted, size: 18)
                    : const Icon(Icons.circle, color: AppColors.orange, size: 9),
            isThreeLine: notification.createdAt != null,
          );
        },
      ),
    );
  }

  IconData _iconFor(String type) {
    final value = type.toLowerCase();
    if (value.contains('kot') || value.contains('kitchen')) return Icons.kitchen_outlined;
    if (value.contains('stock') || value.contains('inventory')) return Icons.inventory_2_outlined;
    if (value.contains('bill') || value.contains('payment')) return Icons.receipt_long_outlined;
    if (value.contains('order')) return Icons.shopping_bag_outlined;
    if (value.contains('sync')) return Icons.sync_problem_outlined;
    return Icons.notifications_outlined;
  }
}
