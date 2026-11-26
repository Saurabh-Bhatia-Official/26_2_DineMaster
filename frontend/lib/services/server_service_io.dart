import 'dart:convert';
import 'dart:io';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_router/shelf_router.dart';
import '../repositories/product_repository.dart';
import 'edge_security_gate.dart';

class ServerService {
  static final ServerService instance = ServerService._init();
  HttpServer? _server;
  final _productRepository = ProductRepository();

  ServerService._init();

  Future<void> startServer() async {
    if (Platform.environment['DINEMASTER_EDGE_SECURITY_ENABLED'] != 'true') {
      print(
        'Edge LAN service disabled; set DINEMASTER_EDGE_SECURITY_ENABLED=true only for isolated development.',
      );
      return;
    }

    final certificatePath = Platform.environment['DINEMASTER_EDGE_TLS_CERT'];
    final privateKeyPath = Platform.environment['DINEMASTER_EDGE_TLS_KEY'];
    if (certificatePath == null ||
        certificatePath.isEmpty ||
        privateKeyPath == null ||
        privateKeyPath.isEmpty) {
      throw StateError(
        'Edge LAN service requires DINEMASTER_EDGE_TLS_CERT and DINEMASTER_EDGE_TLS_KEY. Plain HTTP fallback is disabled.',
      );
    }
    final tlsContext = SecurityContext(withTrustedRoots: true)
      ..useCertificateChain(certificatePath)
      ..usePrivateKey(privateKeyPath);

    final router = Router();

    // Menu Endpoint
    router.get('/api/menu', (Request request) async {
      try {
        final products = await _productRepository.getProducts();
        final jsonList = products.map((p) => p.toMap()).toList();
        return Response.ok(
          jsonEncode(jsonList),
          headers: {'Content-Type': 'application/json'},
        );
      } catch (e) {
        return Response.internalServerError(body: e.toString());
      }
    });

    // Digital Menu Web Page
    router.get('/menu', (Request request) async {
      final html = '''
<!DOCTYPE html>
<html>
<head>
    <title>RestoPro Digital Menu</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 0; padding: 16px; background: #f5f5f5; }
        header { background: #6200ee; color: white; padding: 16px; margin: -16px -16px 16px -16px; text-align: center; }
        .product { background: white; padding: 16px; margin-bottom: 12px; border-radius: 12px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); display: flex; justify-content: space-between; align-items: center; }
        .product-info h3 { margin: 0; font-size: 16px; }
        .product-info p { margin: 4px 0 0 0; color: #666; font-size: 14px; }
        .price { font-weight: bold; color: #6200ee; }
        .btn { background: #6200ee; color: white; border: none; padding: 8px 16px; border-radius: 20px; cursor: pointer; font-weight: bold; }
        .cart-bar { position: fixed; bottom: 0; left: 0; right: 0; background: white; padding: 16px; box-shadow: 0 -2px 10px rgba(0,0,0,0.1); display: flex; justify-content: space-between; align-items: center; }
    </style>
</head>
<body>
    <header>
        <h1>RestoPro</h1>
        <p>Digital Menu</p>
    </header>
    <div id="products">Loading menu...</div>
    <div class="cart-bar">
        <div><span id="cart-count">0</span> Items in Cart</div>
        <button class="btn" onclick="placeOrder()">Place Order</button>
    </div>
    <script>
        let cart = [];
        fetch('/api/menu')
            .then(res => res.json())
            .then(data => {
                const div = document.getElementById('products');
                div.innerHTML = '';
                data.forEach(p => {
                    const el = document.createElement('div');
                    el.className = 'product';
                    el.innerHTML = `
                        <div class="product-info">
                            <h3>\${p.name}</h3>
                            <p>\${p.description || 'Delicious food'}</p>
                            <p class="price">₹\${p.price}</p>
                        </div>
                        <button class="btn" onclick="addToCart(\${p.id}, '\${p.name}', \${p.price})">Add</button>
                    `;
                    div.appendChild(el);
                });
            });
            
        function addToCart(id, name, price) {
            cart.push({id, name, price});
            document.getElementById('cart-count').innerText = cart.length;
        }
        
        function placeOrder() {
            if (cart.length === 0) { alert('Cart is empty!'); return; }
            const urlParams = new URLSearchParams(window.location.search);
            const table = urlParams.get('table') || 'Takeaway';
            
            fetch('/api/order', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    table: table,
                    items: cart
                })
            })
            .then(res => res.json())
            .then(data => {
                alert('Order placed successfully for Table ' + table + '!');
                cart = [];
                document.getElementById('cart-count').innerText = '0';
            });
        }
    </script>
</body>
</html>
''';
      return Response.ok(html, headers: {'Content-Type': 'text/html'});
    });

    // Generic SQL proxy routes are intentionally removed. Legacy callers receive HTTP 426 from the security gate.

    // Fallback or static files (if any)
    router.all('/<ignored|.*>', (Request request) {
      return Response.ok('RestoPro API is running');
    });

    final handler = const Pipeline()
        .addMiddleware(logRequests())
        .addMiddleware(edgeSecurityGate)
        .addHandler(router.call);

    _server = await shelf_io.serve(
      handler,
      InternetAddress.loopbackIPv4,
      8082,
      securityContext: tlsContext,
    );
    print(
      'Secure Edge service running on IP: ${_server!.address.address} Port: ${_server!.port}',
    );

    // The legacy unauthenticated WebSocket listener is retired. Authenticated WSS is not exposed until session validation is implemented.
  }

  Future<void> stopServer() async {
    await _server?.close(force: true);
    print('Server stopped');
  }

  String get serverUrl {
    if (_server == null) return '';
    return 'https://${_server!.address.address}:${_server!.port}';
  }
}
