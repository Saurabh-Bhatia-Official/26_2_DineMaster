import 'package:dine_master/services/edge_host_identity.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('RSA activation proof is bound to activation and nonce', () {
    final identity = EdgeHostIdentity.generate();
    final restored = EdgeHostIdentity.restore(identity.privateComponents);
    final signature = identity.signActivationProof(
      activationId: 'activation-fixture',
      nonce: 'nonce-fixture',
    );

    expect(restored.publicKeyPem, identity.publicKeyPem);
    expect(
      restored.verifyActivationProof(
        activationId: 'activation-fixture',
        nonce: 'nonce-fixture',
        signature: signature,
      ),
      isTrue,
    );
    expect(
      restored.verifyActivationProof(
        activationId: 'another-activation',
        nonce: 'nonce-fixture',
        signature: signature,
      ),
      isFalse,
    );
  });
}
