import 'dart:io' as io;

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:dine_master/core/database/m28_sqlite_migration.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  test('backfills UUID identities additively and is repeatable', () async {
    final db = await databaseFactory.openDatabase(inMemoryDatabasePath);
    addTearDown(db.close);

    await _createLegacyV7Fixture(db);
    final legacyOrderId = await db.insert('orders', {'total_amount': 250.0});
    final legacyPaymentId = await db.insert('payments', {
      'order_id': legacyOrderId,
      'amount': 250.0,
      'payment_mode': 'Cash',
      'payment_time': '2026-01-01T10:00:00Z',
    });

    await M28SqliteMigration.apply(db);
    final firstOrder = (await db.query(
      'orders',
      where: 'id = ?',
      whereArgs: [legacyOrderId],
    )).single;
    final firstOrderSyncId = firstOrder['sync_id'] as String;
    expect(firstOrder['id'], legacyOrderId);
    expect(firstOrder['version'], 1);
    expect(firstOrder['updated_at'], isNotNull);
    expect(firstOrderSyncId, matches(RegExp(r'^[0-9a-f-]{36}$')));

    final payment = (await db.query(
      'payments',
      where: 'id = ?',
      whereArgs: [legacyPaymentId],
    )).single;
    expect(payment['id'], legacyPaymentId);
    expect(payment['event_type'], 'PAYMENT');
    expect(payment['idempotency_key'], payment['sync_id']);
    expect(
      await db.query(
        'sync_identity_map',
        where: 'entity_type = ? AND edge_id = ?',
        whereArgs: ['orders', legacyOrderId],
      ),
      hasLength(1),
    );

    await M28SqliteMigration.apply(db);
    final repeatedOrder = (await db.query(
      'orders',
      where: 'id = ?',
      whereArgs: [legacyOrderId],
    )).single;
    expect(repeatedOrder['sync_id'], firstOrderSyncId);
    expect(repeatedOrder['version'], 1);

    final newOrderId = await db.insert('orders', {'total_amount': 10.0});
    final newOrder = (await db.query(
      'orders',
      where: 'id = ?',
      whereArgs: [newOrderId],
    )).single;
    expect(newOrder['sync_id'], isA<String>());
    expect(newOrder['updated_at'], isNotNull);
    final pendingCreate = await db.query(
      'sync_outbox',
      where: 'entity_type = ? AND entity_sync_id = ?',
      whereArgs: ['orders', newOrder['sync_id']],
    );
    expect(pendingCreate, hasLength(1));
    expect(pendingCreate.single['status'], 'PENDING');
    expect(pendingCreate.single['operation'], 'UPSERT');
    expect(
      pendingCreate.single['change_id'],
      matches(RegExp(r'^[0-9a-f-]{36}$')),
    );
    expect(
      await db.query(
        'sync_identity_map',
        where: 'entity_type = ? AND edge_id = ?',
        whereArgs: ['orders', newOrderId],
      ),
      hasLength(1),
    );

    await db.update(
      'orders',
      {'total_amount': 11.0},
      where: 'id = ?',
      whereArgs: [newOrderId],
    );
    final updatedOrder = (await db.query(
      'orders',
      where: 'id = ?',
      whereArgs: [newOrderId],
    )).single;
    expect(updatedOrder['version'], 2);
    expect(
      await db.query(
        'sync_outbox',
        where: 'entity_type = ? AND entity_sync_id = ?',
        whereArgs: ['orders', newOrder['sync_id']],
      ),
      hasLength(2),
    );

    await expectLater(
      db.update(
        'orders',
        {'sync_id': '00000000-0000-4000-8000-000000000001'},
        where: 'id = ?',
        whereArgs: [newOrderId],
      ),
      throwsA(isA<DatabaseException>()),
    );
  });

  test(
    'order and item writes enqueue atomically with stable entity identities',
    () async {
      final db = await databaseFactory.openDatabase(inMemoryDatabasePath);
      addTearDown(db.close);
      await _createLegacyV7Fixture(db);
      await M28SqliteMigration.apply(db);

      final orderId = await db.transaction((txn) async {
        final productId = await txn.insert('products', {
          'name': 'Synthetic chai',
        });
        final orderId = await txn.insert('orders', {'total_amount': 42.0});
        await txn.insert('order_items', {
          'order_id': orderId,
          'product_id': productId,
          'quantity': 2,
          'price': 21.0,
        });
        return orderId;
      });
      final order = (await db.query(
        'orders',
        where: 'id=?',
        whereArgs: [orderId],
      )).single;
      final orderEvents = await db.query(
        'sync_outbox',
        where: 'entity_sync_id=?',
        whereArgs: [order['sync_id']],
      );
      expect(orderEvents, hasLength(1));
      final itemEvents = await db.query(
        'sync_outbox',
        where: "entity_type='order_items'",
      );
      expect(itemEvents, hasLength(1));
      expect(itemEvents.single['status'], 'PENDING');
      expect(itemEvents.single['payload_json'], contains('order_sync_id'));

      try {
        await db.transaction((txn) async {
          await txn.insert('orders', {'total_amount': 99.0});
          throw StateError('synthetic transaction interruption');
        });
      } on StateError {
        // Expected rollback.
      }
      expect(
        await db.rawQuery(
          'SELECT COUNT(*) AS n FROM orders WHERE total_amount=99',
        ),
        [
          {'n': 0},
        ],
      );
      expect(await db.rawQuery('SELECT COUNT(*) AS n FROM sync_outbox'), [
        {'n': 2},
      ]);
    },
  );

  test(
    'backs up populated legacy data and permits restoring the backup',
    () async {
      final directory = await io.Directory.systemTemp.createTemp('m28-sqlite-');
      addTearDown(() => directory.delete(recursive: true));
      final path = '${directory.path}${io.Platform.pathSeparator}legacy.sqlite';
      final db = await databaseFactory.openDatabase(path);
      await _createLegacyV7Fixture(db);
      final orderId = await db.insert('orders', {'total_amount': 250.0});
      await db.insert('order_items', {'order_id': orderId});
      await db.insert('payments', {
        'order_id': orderId,
        'amount': 250.0,
        'payment_mode': 'Cash',
        'payment_time': '2026-01-01T10:00:00Z',
      });
      await db.close();

      final backupPath = await M28SqliteMigration.backupBeforeUpgrade(path);
      expect(backupPath, isNotNull);
      final backup = await databaseFactory.openDatabase(
        backupPath!,
        options: OpenDatabaseOptions(readOnly: true),
      );
      expect(await backup.rawQuery('PRAGMA integrity_check'), [
        {'integrity_check': 'ok'},
      ]);
      expect(await backup.rawQuery('SELECT COUNT(*) AS n FROM orders'), [
        {'n': 1},
      ]);
      expect(
        (await backup.rawQuery(
          'SELECT SUM(amount) AS amount FROM payments',
        )).single['amount'],
        250.0,
      );
      await backup.close();

      final live = await databaseFactory.openDatabase(path);
      await M28SqliteMigration.apply(live);
      expect(await live.rawQuery('PRAGMA foreign_key_check'), isEmpty);
      await live.close();

      final upgradedPath =
          '${directory.path}${io.Platform.pathSeparator}upgraded.sqlite';
      await io.File(path).copy(upgradedPath);
      await io.File(backupPath).copy(path);
      final restored = await databaseFactory.openDatabase(
        path,
        options: OpenDatabaseOptions(readOnly: true),
      );
      expect(await restored.rawQuery('PRAGMA integrity_check'), [
        {'integrity_check': 'ok'},
      ]);
      expect(await restored.rawQuery('SELECT COUNT(*) AS n FROM order_items'), [
        {'n': 1},
      ]);
      await restored.close();
      expect(await io.File(upgradedPath).exists(), isTrue);
    },
  );

  test(
    'migration transaction rolls back partial DDL on UUID collision',
    () async {
      final db = await databaseFactory.openDatabase(inMemoryDatabasePath);
      addTearDown(db.close);
      await _createLegacyV7Fixture(db);
      await db.insert('orders', {'total_amount': 12.0});
      await db.insert('orders', {'total_amount': 13.0});
      await db.execute('ALTER TABLE orders ADD COLUMN sync_id TEXT');
      await db.execute(
        "UPDATE orders SET sync_id = '00000000-0000-4000-8000-000000000001'",
      );

      await expectLater(
        db.transaction((txn) => M28SqliteMigration.apply(txn)),
        throwsA(isA<DatabaseException>()),
      );

      final columns = await db.rawQuery('PRAGMA table_info("orders")');
      expect(columns.any((column) => column['name'] == 'version'), isFalse);
      expect(
        await db.rawQuery(
          "SELECT name FROM sqlite_master WHERE type='table' AND name='sync_identity_map'",
        ),
        isEmpty,
      );
      expect(await db.rawQuery('SELECT COUNT(*) AS n FROM orders'), [
        {'n': 2},
      ]);
    },
  );
}

Future<void> _createLegacyV7Fixture(Database db) async {
  await db.execute('PRAGMA foreign_keys = ON');
  await db.execute(
    'CREATE TABLE restaurants (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)',
  );
  await db.execute(
    'CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT)',
  );
  await db.execute(
    'CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)',
  );
  await db.execute(
    'CREATE TABLE tables (id INTEGER PRIMARY KEY AUTOINCREMENT, table_number TEXT, restaurant_id INTEGER REFERENCES restaurants(id))',
  );
  await db.execute(
    'CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)',
  );
  await db.execute(
    'CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, category_id INTEGER REFERENCES categories(id), restaurant_id INTEGER REFERENCES restaurants(id))',
  );
  await db.execute(
    'CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, total_amount REAL, table_id INTEGER REFERENCES tables(id), customer_id INTEGER REFERENCES customers(id), waiter_id INTEGER REFERENCES users(id), restaurant_id INTEGER REFERENCES restaurants(id), status TEXT, type TEXT, order_time TEXT, notes TEXT, discount_amount REAL, customer_name TEXT, customer_phone TEXT, guest_count INTEGER)',
  );
  await db.execute(
    'CREATE TABLE order_items (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER REFERENCES orders(id), product_id INTEGER REFERENCES products(id), quantity INTEGER, price REAL, notes TEXT, status TEXT, kot_id INTEGER REFERENCES kot(id))',
  );
  await db.execute(
    'CREATE TABLE kot (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER REFERENCES orders(id))',
  );
  await db.execute(
    'CREATE TABLE order_status_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER REFERENCES orders(id))',
  );
  await db.execute('''
    CREATE TABLE payments (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      order_id INTEGER REFERENCES orders(id),
      amount REAL,
      payment_mode TEXT,
      payment_time TEXT
    )
  ''');
  await db.execute(
    'CREATE TABLE bookings (id INTEGER PRIMARY KEY AUTOINCREMENT, booking_time TEXT, table_id INTEGER REFERENCES tables(id), customer_id INTEGER REFERENCES customers(id))',
  );
  await db.execute(
    'CREATE TABLE inventory (id INTEGER PRIMARY KEY AUTOINCREMENT, item_name TEXT, restaurant_id INTEGER REFERENCES restaurants(id))',
  );
}
