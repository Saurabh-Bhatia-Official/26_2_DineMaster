import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:pointycastle/export.dart';

class EdgeHostIdentity {
  EdgeHostIdentity(this.privateKey, this.publicKey);
  final RSAPrivateKey privateKey;
  final RSAPublicKey publicKey;

  static EdgeHostIdentity generate() {
    final rng = FortunaRandom();
    final seed = Uint8List.fromList(
      List<int>.generate(32, (_) => Random.secure().nextInt(256)),
    );
    rng.seed(KeyParameter(seed));
    final generator = RSAKeyGenerator()
      ..init(
        ParametersWithRandom(
          RSAKeyGeneratorParameters(BigInt.from(65537), 2048, 64),
          rng,
        ),
      );
    final pair = generator.generateKeyPair();
    return EdgeHostIdentity(pair.privateKey, pair.publicKey);
  }

  String get publicKeyPem {
    final pkcs1 = _sequence([
      ..._integer(publicKey.modulus!),
      ..._integer(publicKey.exponent!),
    ]);
    const rsaAlgorithm = [
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      0x86,
      0x48,
      0x86,
      0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00,
    ];
    final bitString = _tagged(0x03, [0, ...pkcs1]);
    return _pem('PUBLIC KEY', _sequence([...rsaAlgorithm, ...bitString]));
  }

  Map<String, String> get privateComponents => {
    'n': _encodeInteger(privateKey.modulus!),
    'd': _encodeInteger(privateKey.privateExponent!),
    'p': _encodeInteger(privateKey.p!),
    'q': _encodeInteger(privateKey.q!),
    'e': _encodeInteger(privateKey.publicExponent!),
  };

  static EdgeHostIdentity restore(Map<String, String> values) {
    final n = _decodeInteger(values['n']!);
    final d = _decodeInteger(values['d']!);
    final p = _decodeInteger(values['p']!);
    final q = _decodeInteger(values['q']!);
    final e = _decodeInteger(values['e']!);
    return EdgeHostIdentity(RSAPrivateKey(n, d, p, q, e), RSAPublicKey(n, e));
  }

  String signActivationProof({
    required String activationId,
    required String nonce,
  }) {
    final fingerprint = sha256.convert(_publicDer).toString();
    final message =
        'dinemaster-edge-host-activation:v1\n$activationId\n$nonce\n$fingerprint';
    final signer = RSASigner(SHA256Digest(), '0609608648016503040201')
      ..init(true, PrivateKeyParameter<RSAPrivateKey>(privateKey));
    return base64Encode(
      signer.generateSignature(Uint8List.fromList(utf8.encode(message))).bytes,
    );
  }

  bool verifyActivationProof({
    required String activationId,
    required String nonce,
    required String signature,
  }) {
    final fingerprint = sha256.convert(_publicDer).toString();
    final message =
        'dinemaster-edge-host-activation:v1\n$activationId\n$nonce\n$fingerprint';
    final verifier = RSASigner(SHA256Digest(), '0609608648016503040201')
      ..init(false, PublicKeyParameter<RSAPublicKey>(publicKey));
    try {
      return verifier.verifySignature(
        Uint8List.fromList(utf8.encode(message)),
        RSASignature(base64Decode(signature)),
      );
    } catch (_) {
      return false;
    }
  }

  List<int> get _publicDer {
    final pkcs1 = _sequence([
      ..._integer(publicKey.modulus!),
      ..._integer(publicKey.exponent!),
    ]);
    const rsaAlgorithm = [
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      0x86,
      0x48,
      0x86,
      0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00,
    ];
    return _sequence([
      ...rsaAlgorithm,
      ..._tagged(0x03, [0, ...pkcs1]),
    ]);
  }

  static String _encodeInteger(BigInt value) =>
      base64UrlEncode(_unsigned(value));
  static BigInt _decodeInteger(String value) =>
      _fromUnsigned(base64Url.decode(base64Url.normalize(value)));
  static List<int> _unsigned(BigInt value) {
    var hex = value.toRadixString(16);
    if (hex.length.isOdd) hex = '0$hex';
    return List<int>.generate(
      hex.length ~/ 2,
      (i) => int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16),
    );
  }

  static BigInt _fromUnsigned(List<int> bytes) => BigInt.parse(
    bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join(),
    radix: 16,
  );
  static List<int> _integer(BigInt value) {
    var bytes = _unsigned(value);
    if (bytes.isEmpty) bytes = [0];
    if ((bytes.first & 0x80) != 0) bytes = [0, ...bytes];
    return _tagged(0x02, bytes);
  }

  static List<int> _sequence(List<int> content) => _tagged(0x30, content);
  static List<int> _tagged(int tag, List<int> content) => [
    tag,
    ..._length(content.length),
    ...content,
  ];
  static List<int> _length(int length) {
    if (length < 128) return [length];
    final bytes = _unsigned(BigInt.from(length));
    return [0x80 | bytes.length, ...bytes];
  }

  static String _pem(String label, List<int> bytes) {
    final encoded = base64Encode(bytes);
    final lines = <String>[];
    for (var i = 0; i < encoded.length; i += 64) {
      lines.add(encoded.substring(i, min(i + 64, encoded.length)));
    }
    return '-----BEGIN $label-----\n${lines.join('\n')}\n-----END $label-----';
  }
}
