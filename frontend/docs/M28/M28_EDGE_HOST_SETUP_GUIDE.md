# M28 Edge Host Isolated Prototype Setup

**Status:** Development-only guide for the current fail-closed prototype plus disabled host-enrollment foundation. It does not configure a usable Edge host, issue credentials, or enable LAN operations.  
**Restrictions:** Use synthetic data and a local test database. Never use production/Neon URLs, keys, certificates, or database files. Automatic synchronization is not implemented.

## Current prototype limits

- The Edge LAN service is **disabled by default**.
- When explicitly enabled, it requires a TLS certificate and private key and binds to `127.0.0.1:8082` only. It is not reachable by LAN clients.
- Generic network SQL is retired. `/api/db/*` returns `426 LEGACY_SQL_DISABLED`; Flutter `NetworkDatabase` throws `CLIENT_UPGRADE_REQUIRED` without making a network request.
- `POST /api/order` returns `426 GUEST_CAPABILITY_REQUIRED` until scoped guest authorization and a domain handler exist.
- Only the minimal `GET /api/edge/v1/capabilities` endpoint is available. It reports that authenticated domain operations and automatic synchronization are disabled.
- Port 8083 is not bound. Authenticated WSS is not implemented.
- Edge user grants, signed offline authorization, host rebind/revocation, and epoch recovery are not implemented. Offline login is unavailable until signed grants exist. Host activation has only a disabled development foundation; it is not a usable host setup flow.
- Spring host activation routes are present but disabled unless `EDGE_CONTROL_PLANE_ENABLED=true`. V35 must be validated on an isolated disposable PostgreSQL database before development use. Enrollment currently returns identity only and no usable credential; do not enable it for operational use.
- Do not expose this prototype to another device, even on a private LAN.

## 1. Create a local development certificate

Use a local OpenSSL installation to create a short-lived certificate with a localhost name. Run from a private local development directory outside source control:

```powershell
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 `
  -keyout edge-dev.key -out edge-dev.crt `
  -subj "/CN=localhost" `
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Keep `edge-dev.key` private and out of the repository. Do not disable client certificate validation. For command-line checks, use `curl.exe --cacert edge-dev.crt`; never use a TLS verification bypass.

## 2. Enable the local Edge process

From the `frontend` directory, set environment variables only in the current PowerShell session, then run the Windows app:

```powershell
$env:DINEMASTER_EDGE_SECURITY_ENABLED = 'true'
$env:DINEMASTER_EDGE_TLS_CERT = 'C:\path\to\edge-dev.crt'
$env:DINEMASTER_EDGE_TLS_KEY = 'C:\path\to\edge-dev.key'
flutter run -d windows
```

If the feature flag is absent or not `true`, no Edge listener starts. If either certificate path is missing or invalid, startup fails closed; the server does not fall back to HTTP. The listener binds only to loopback.

## 3. Verify only the implemented prototype endpoints

In another terminal, from the certificate directory:

```powershell
curl.exe --cacert edge-dev.crt https://localhost:8082/api/edge/v1/capabilities
```

The response should report `authenticationRequired: true`, `domainOperationsAvailable: false`, and `automaticSynchronizationEnabled: false`.

To verify legacy SQL is denied, submit a synthetic request:

```powershell
curl.exe --cacert edge-dev.crt -H "Content-Type: application/json" `
  -d '{"sql":"SELECT 1"}' https://localhost:8082/api/db/rawQuery
```

Expected result: HTTP `426` with `LEGACY_SQL_DISABLED`. Do not submit a real SQL operation. A guest order POST similarly returns `426 GUEST_CAPABILITY_REQUIRED`.

## 4. Test Spring Edge-authoritative mode locally

The backend guard is disabled by default. To test the opt-in behavior against the local test profile only:

```powershell
$env:EDGE_AUTHORITATIVE_MODE = 'true'
cd ..\backend
.\mvnw.cmd -q test
```

With the mode enabled, mutations under orders, kitchen tickets, tables, reservations, inventory, and customers are rejected with `503 EDGE_REQUIRED`; reads and routes outside those prefixes pass through. This guard is not yet a complete Spring route inventory or an Edge relay.

To turn the development guard back off in the current PowerShell session:

```powershell
Remove-Item Env:EDGE_AUTHORITATIVE_MODE -ErrorAction SilentlyContinue
```

## 5. Stop the prototype

Stop the Flutter process with `Ctrl+C`, then clear the session-only environment variables:

```powershell
Remove-Item Env:DINEMASTER_EDGE_SECURITY_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:DINEMASTER_EDGE_TLS_CERT -ErrorAction SilentlyContinue
Remove-Item Env:DINEMASTER_EDGE_TLS_KEY -ErrorAction SilentlyContinue
```

## Release blockers

Formal Security, Product, Operations, Finance, API-owner, client-owner, data-owner, and other applicable approvals remain release gates. The prototype is not a registered host and must not be used for branch operations. Complete Phase 3A host identity, JWT/offline authorization, trust enrollment, authenticated WSS, epoch recovery, route classification, integration tests, and Phase 3B domain services before any LAN/production rollout.
