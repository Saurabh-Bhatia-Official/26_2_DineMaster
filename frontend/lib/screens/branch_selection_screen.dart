import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../core/api/api_mode.dart';
import '../core/database/database_helper.dart';
import '../repositories/restaurant_repository.dart';

const _orange = Color(0xFFE56A2F);
const _ink = Color(0xFF202326);
const _muted = Color(0xFF71777D);
const _line = Color(0xFFE7E8EA);
const _canvas = Color(0xFFF6F6F4);

class BranchSelectionScreen extends StatefulWidget {
  const BranchSelectionScreen({super.key, this.restaurantsLoader});

  /// Keeps widget tests independent from the production repository.
  final Future<List<Map<String, dynamic>>> Function()? restaurantsLoader;

  @override
  State<BranchSelectionScreen> createState() => _BranchSelectionScreenState();
}

class _BranchSelectionScreenState extends State<BranchSelectionScreen> {
  final _searchController = TextEditingController();
  List<Map<String, dynamic>> _restaurants = [];
  bool _isLoading = true;
  bool _isSelecting = false;
  int? _selectedBranchId;
  int? _pendingBranchId;
  int? _hoveredBranchId;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _loadRestaurants();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadRestaurants() async {
    setState(() {
      _isLoading = true;
      _loadError = null;
    });

    try {
      final restaurants =
          await (widget.restaurantsLoader?.call() ??
              RestaurantRepository().getRestaurants());
      if (!mounted) return;

      final activeBranchId = DatabaseHelper.currentRestaurantId;
      setState(() {
        _restaurants = restaurants;
        _selectedBranchId =
            restaurants.any((branch) => _branchId(branch) == activeBranchId)
            ? activeBranchId
            : null;
        _isLoading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _loadError = 'We couldn’t load your branches. Please try again.';
      });
    }
  }

  List<Map<String, dynamic>> get _filteredRestaurants {
    final query = _searchController.text.trim().toLowerCase();
    if (query.isEmpty) return _restaurants;

    return _restaurants.where((branch) {
      final searchableText = [
        _value(branch, 'name'),
        _value(branch, 'code'),
        _value(branch, 'address'),
        _value(branch, 'phone'),
        _value(branch, 'timezone'),
      ].join(' ').toLowerCase();
      return searchableText.contains(query);
    }).toList();
  }

  String _value(Map<String, dynamic> branch, String key) =>
      branch[key]?.toString().trim() ?? '';

  int? _branchId(Map<String, dynamic> branch) {
    final value = branch['id'];
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  Future<void> _selectBranch(Map<String, dynamic> branch) async {
    final id = _branchId(branch);
    if (id == null) {
      _showMessage(
        'This branch is missing an identifier. Please refresh and try again.',
      );
      return;
    }
    if (_isSelecting) return;

    final previousBranchId = DatabaseHelper.currentRestaurantId;
    setState(() {
      _selectedBranchId = id;
      _pendingBranchId = id;
      _isSelecting = true;
    });

    try {
      DatabaseHelper.currentRestaurantId = id;
      await ApiMode.setCurrentRestaurantId(id);
      if (mounted) context.go('/dashboard');
    } catch (_) {
      DatabaseHelper.currentRestaurantId = previousBranchId;
      if (!mounted) return;
      setState(() {
        _selectedBranchId = previousBranchId;
        _pendingBranchId = null;
        _isSelecting = false;
      });
      _showMessage('We couldn’t select that branch. Please try again.');
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _canvas,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final narrow = constraints.maxWidth < 620;
            final columns = constraints.maxWidth >= 1050
                ? 3
                : constraints.maxWidth >= 680
                ? 2
                : 1;
            final sidePadding = narrow ? 20.0 : 36.0;

            return SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(sidePadding, 20, sidePadding, 40),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1200),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _topBar(narrow: narrow),
                      const SizedBox(height: 24),
                      _workspaceBanner(narrow: narrow),
                      const SizedBox(height: 30),
                      _directoryHeader(narrow: narrow),
                      const SizedBox(height: 16),
                      _branchContent(columns: columns),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _topBar({required bool narrow}) => Row(
    children: [
      _logo(size: 44, radius: 13),
      const SizedBox(width: 12),
      const Expanded(
        child: Text(
          'DineMaster',
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: _ink,
            fontSize: 20,
            fontWeight: FontWeight.w700,
            letterSpacing: -.4,
          ),
        ),
      ),
      if (!narrow)
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
          decoration: BoxDecoration(
            color: Colors.white,
            border: Border.all(color: _line),
            borderRadius: BorderRadius.circular(20),
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.verified_user_outlined, color: _orange, size: 15),
              SizedBox(width: 6),
              Text(
                'Secure workspace',
                style: TextStyle(
                  color: _muted,
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
    ],
  );

  Widget _workspaceBanner({required bool narrow}) => Container(
    padding: EdgeInsets.all(narrow ? 24 : 34),
    decoration: BoxDecoration(
      color: _ink,
      borderRadius: BorderRadius.circular(22),
      boxShadow: const [
        BoxShadow(
          color: Color(0x180D1117),
          blurRadius: 28,
          offset: Offset(0, 14),
        ),
      ],
    ),
    child: narrow
        ? Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _bannerCopy(),
              const SizedBox(height: 24),
              _branchSummary(),
            ],
          )
        : Row(
            children: [
              Expanded(child: _bannerCopy()),
              const SizedBox(width: 36),
              _branchSummary(),
            ],
          ),
  );

  Widget _bannerCopy() => const Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        'BRANCH DIRECTORY',
        style: TextStyle(
          color: Color(0xFFFFA476),
          fontSize: 10,
          fontWeight: FontWeight.w700,
          letterSpacing: 1.5,
        ),
      ),
      SizedBox(height: 12),
      Text(
        'Find the floor\nyou need to run.',
        style: TextStyle(
          color: Colors.white,
          fontSize: 32,
          height: 1.13,
          fontWeight: FontWeight.w700,
          letterSpacing: -1,
        ),
      ),
      SizedBox(height: 12),
      Text(
        'Pick a branch to access the service, sales, and operations that belong to it.',
        style: TextStyle(color: Color(0xFFB9BABC), fontSize: 14, height: 1.55),
      ),
    ],
  );

  Widget _branchSummary() => Container(
    width: 190,
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .08),
      border: Border.all(color: Colors.white.withValues(alpha: .14)),
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Icon(
          Icons.storefront_rounded,
          color: Color(0xFFFFA476),
          size: 24,
        ),
        const SizedBox(height: 20),
        Text(
          _isLoading ? '—' : '${_restaurants.length}',
          style: const TextStyle(
            color: Colors.white,
            fontSize: 26,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 2),
        const Text(
          'branches available',
          style: TextStyle(color: Color(0xFFB9BABC), fontSize: 12),
        ),
      ],
    ),
  );

  Widget _directoryHeader({required bool narrow}) {
    final count = _filteredRestaurants.length;
    final availability = _isLoading
        ? 'Loading branches…'
        : '$count ${count == 1 ? 'branch' : 'branches'} available';

    final heading = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Choose a branch',
          style: TextStyle(
            color: _ink,
            fontSize: 23,
            fontWeight: FontWeight.w700,
            letterSpacing: -.5,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          availability,
          style: const TextStyle(
            color: _muted,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
    final search = SizedBox(width: narrow ? null : 360, child: _searchField());

    return narrow
        ? Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [heading, const SizedBox(height: 14), search],
          )
        : Row(
            children: [
              Expanded(child: heading),
              search,
            ],
          );
  }

  Widget _searchField() => TextField(
    controller: _searchController,
    onChanged: (_) => setState(() {}),
    textInputAction: TextInputAction.search,
    decoration: InputDecoration(
      hintText: 'Search branches, locations, or codes',
      hintStyle: const TextStyle(color: Color(0xFFAAADB0), fontSize: 13),
      prefixIcon: const Icon(
        Icons.search_rounded,
        color: Color(0xFF8A8F94),
        size: 20,
      ),
      suffixIcon: _searchController.text.isEmpty
          ? null
          : IconButton(
              tooltip: 'Clear search',
              onPressed: () {
                _searchController.clear();
                setState(() {});
              },
              icon: const Icon(Icons.close_rounded, color: _muted, size: 19),
            ),
      filled: true,
      fillColor: Colors.white,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 15),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: _line),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: _line),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: _orange, width: 1.4),
      ),
    ),
  );

  Widget _branchContent({required int columns}) {
    if (_isLoading) {
      return const SizedBox(
        height: 280,
        child: Center(
          child: CircularProgressIndicator(color: _orange, strokeWidth: 2.5),
        ),
      );
    }

    if (_loadError != null) {
      return _messageState(
        icon: Icons.error_outline_rounded,
        title: 'Branches are unavailable',
        message: _loadError!,
        actionLabel: 'Try again',
        onAction: _loadRestaurants,
        error: true,
      );
    }

    if (_restaurants.isEmpty) {
      return _messageState(
        icon: Icons.storefront_outlined,
        title: 'No branches available',
        message: 'Ask your administrator to assign a branch to your account.',
      );
    }

    final branches = _filteredRestaurants;
    if (branches.isEmpty) {
      return _messageState(
        icon: Icons.search_off_rounded,
        title: 'No matching branches',
        message: 'Try searching by branch name, code, address, or phone.',
      );
    }

    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: branches.length,
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: columns,
        crossAxisSpacing: 16,
        mainAxisSpacing: 16,
        mainAxisExtent: columns == 1 ? 248 : 260,
      ),
      itemBuilder: (context, index) => _branchCard(branches[index]),
    );
  }

  Widget _messageState({
    required IconData icon,
    required String title,
    required String message,
    String? actionLabel,
    VoidCallback? onAction,
    bool error = false,
  }) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 36),
    decoration: BoxDecoration(
      color: error ? const Color(0xFFFFF1EF) : Colors.white,
      border: Border.all(color: error ? const Color(0xFFF8D0CB) : _line),
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: error ? const Color(0xFFB84032) : _orange, size: 30),
        const SizedBox(height: 12),
        Text(
          title,
          style: TextStyle(
            color: error ? const Color(0xFF9D352A) : _ink,
            fontSize: 15,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          message,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: error ? const Color(0xFF9D352A) : _muted,
            fontSize: 12,
            height: 1.45,
          ),
        ),
        if (actionLabel != null && onAction != null) ...[
          const SizedBox(height: 12),
          TextButton.icon(
            onPressed: onAction,
            style: TextButton.styleFrom(foregroundColor: _orange),
            icon: const Icon(Icons.refresh_rounded, size: 17),
            label: Text(actionLabel),
          ),
        ],
      ],
    ),
  );

  Widget _branchCard(Map<String, dynamic> branch) {
    final id = _branchId(branch);
    final isSelected = _selectedBranchId == id;
    final isPending = _pendingBranchId == id;
    final isHovered = _hoveredBranchId == id;
    final isActive = branch['active'] != false;
    final name = _value(branch, 'name').isEmpty
        ? 'Branch ${id ?? ''}'.trim()
        : _value(branch, 'name');
    final address = _value(branch, 'address');
    final code = _value(branch, 'code');
    final phone = _value(branch, 'phone');

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hoveredBranchId = id),
      onExit: (_) {
        if (_hoveredBranchId == id) setState(() => _hoveredBranchId = null);
      },
      child: Semantics(
        button: true,
        label: 'Select $name',
        child: AnimatedScale(
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOut,
          scale: isHovered && !isSelected ? 1.012 : 1,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOut,
            decoration: BoxDecoration(
              color: isSelected ? const Color(0xFFFFF6F1) : Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: isSelected ? _orange : _line,
                width: isSelected ? 1.4 : 1,
              ),
              boxShadow: [
                BoxShadow(
                  color: isSelected
                      ? const Color(0x22E56A2F)
                      : isHovered
                      ? const Color(0x160D1117)
                      : const Color(0x0D0D1117),
                  blurRadius: isHovered || isSelected ? 22 : 12,
                  offset: Offset(0, isHovered ? 10 : 6),
                ),
              ],
            ),
            child: Material(
              color: Colors.transparent,
              child: InkWell(
                onTap: _isSelecting ? null : () => _selectBranch(branch),
                borderRadius: BorderRadius.circular(16),
                child: Stack(
                  children: [
                    Positioned(
                      top: 0,
                      right: 0,
                      child: Container(
                        width: 76,
                        height: 6,
                        decoration: BoxDecoration(
                          color: isSelected ? _orange : const Color(0xFFFFD4BE),
                          borderRadius: const BorderRadius.only(
                            topRight: Radius.circular(15),
                            bottomLeft: Radius.circular(8),
                          ),
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(18),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Container(
                                width: 44,
                                height: 44,
                                decoration: BoxDecoration(
                                  color: isSelected
                                      ? _orange
                                      : const Color(0xFFFFEEE5),
                                  borderRadius: BorderRadius.circular(13),
                                ),
                                child: Icon(
                                  Icons.storefront_rounded,
                                  color: isSelected ? Colors.white : _orange,
                                  size: 22,
                                ),
                              ),
                              const Spacer(),
                              _statusChip(isActive),
                            ],
                          ),
                          const SizedBox(height: 16),
                          Text(
                            name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: _ink,
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -.2,
                            ),
                          ),
                          const SizedBox(height: 7),
                          _detailLine(
                            Icons.location_on_outlined,
                            address.isEmpty
                                ? 'Location details unavailable'
                                : address,
                          ),
                          if (phone.isNotEmpty) ...[
                            const SizedBox(height: 5),
                            _detailLine(Icons.phone_outlined, phone),
                          ],
                          const Spacer(),
                          Row(
                            children: [
                              if (code.isNotEmpty) _codePill(code),
                              const Spacer(),
                              AnimatedSwitcher(
                                duration: const Duration(milliseconds: 160),
                                child: isPending
                                    ? const SizedBox(
                                        key: ValueKey('loading'),
                                        width: 38,
                                        height: 38,
                                        child: Padding(
                                          padding: EdgeInsets.all(8),
                                          child: CircularProgressIndicator(
                                            color: _orange,
                                            strokeWidth: 2.2,
                                          ),
                                        ),
                                      )
                                    : FilledButton.icon(
                                        key: ValueKey(
                                          isSelected ? 'selected' : 'select',
                                        ),
                                        onPressed: _isSelecting
                                            ? null
                                            : () => _selectBranch(branch),
                                        style: FilledButton.styleFrom(
                                          backgroundColor: isSelected
                                              ? _ink
                                              : _orange,
                                          foregroundColor: Colors.white,
                                          padding: const EdgeInsets.symmetric(
                                            horizontal: 12,
                                            vertical: 10,
                                          ),
                                          minimumSize: Size.zero,
                                          tapTargetSize:
                                              MaterialTapTargetSize.shrinkWrap,
                                          shape: RoundedRectangleBorder(
                                            borderRadius: BorderRadius.circular(
                                              9,
                                            ),
                                          ),
                                        ),
                                        icon: Icon(
                                          isSelected
                                              ? Icons.check_rounded
                                              : Icons.arrow_forward_rounded,
                                          size: 15,
                                        ),
                                        label: Text(
                                          isSelected
                                              ? 'Selected'
                                              : 'Select branch',
                                          style: const TextStyle(
                                            fontSize: 11,
                                            fontWeight: FontWeight.w700,
                                          ),
                                        ),
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
        ),
      ),
    );
  }

  Widget _detailLine(IconData icon, String value) => Row(
    children: [
      Icon(icon, size: 15, color: _muted),
      const SizedBox(width: 6),
      Expanded(
        child: Text(
          value,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: _muted, fontSize: 12),
        ),
      ),
    ],
  );

  Widget _statusChip(bool isActive) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
    decoration: BoxDecoration(
      color: isActive ? const Color(0xFFE9F7EF) : const Color(0xFFF2F3F3),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          Icons.circle,
          size: 7,
          color: isActive ? const Color(0xFF278B54) : _muted,
        ),
        const SizedBox(width: 5),
        Text(
          isActive ? 'Active' : 'Inactive',
          style: TextStyle(
            color: isActive ? const Color(0xFF278B54) : _muted,
            fontSize: 10,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    ),
  );

  Widget _codePill(String code) => Container(
    constraints: const BoxConstraints(maxWidth: 100),
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
    decoration: BoxDecoration(
      color: const Color(0xFFF2F3F3),
      borderRadius: BorderRadius.circular(7),
    ),
    child: Text(
      code,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: const TextStyle(
        color: _muted,
        fontSize: 10,
        fontWeight: FontWeight.w700,
        letterSpacing: .2,
      ),
    ),
  );

  Widget _logo({required double size, required double radius}) => ClipRRect(
    borderRadius: BorderRadius.circular(radius),
    child: Image.asset(
      'assets/images/logo.jpg',
      width: size,
      height: size,
      fit: BoxFit.cover,
    ),
  );
}
