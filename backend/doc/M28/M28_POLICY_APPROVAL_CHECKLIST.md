# M28 Phase 3A Development Policy Review and Approval

**Purpose:** Record development policy proposals separately from domain-owner decisions and production rollout authorization. This document does not itself grant approval.

**Current status:** DineMaster, role “Senior developer,” authorized an isolated development prototype on 2026-11-24 using the proposed policies as technical assumptions, local test databases, synthetic fixtures, and development-only credentials. This execution authorization does not claim formal Security, Product, Operations, Finance, Data-owner, API-owner, or client-owner approval. Those remain release gates. Prototype work must stay fail-closed and disabled by default where noted.

## 1. Proposed development decisions

These reflect the baseline provided for review. Approved items are marked below; this is development-only approval and does not authorize production deployment.

| Area | Proposed development baseline | Status |
|---|---|---|
| Token separation and signing | Use RS256; separate cloud user tokens, Edge user grants, and Edge host credentials. | Technical assumption for isolated prototype; formal owner approval pending release |
| Token lifetimes | Access token 15 minutes; refresh credential maximum 7 days with rotation and reuse detection; offline user grant maximum 12 hours. | Technical assumption for isolated prototype; formal owner approval pending release |
| Activation and local credentials | Device activation code expires in 15 minutes; new local password verifiers use Argon2id; remove base64 authorization tokens; use OS-protected credential storage. | Technical assumption for isolated prototype; formal owner approval pending release |
| Transport | Require HTTPS and authenticated WSS. Never disable TLS certificate validation. | Technical assumption for isolated prototype; formal owner approval pending release |
| Authorization and fencing | Reject expired/invalid credentials, wrong tenant or branch, and stale epochs. Only the authorized Edge host owns branch operational writes. | Technical assumption for isolated prototype; formal owner approval pending release |
| Host lifecycle | One active registered Edge host per branch. Host replacement requires authorized rebind, credential revocation, and epoch increment. | Technical assumption for isolated prototype; formal owner approval pending release |
| Offline operation | Offline work requires a valid signed grant. No independent client writes if Edge is unavailable. | Technical assumption for isolated prototype; formal owner approval pending release |
| Guest and Web access | Guest QR ordering requires a separately scoped capability token. Flutter Web is online-only; operational writes stay disabled until an authorized Edge relay exists. | Technical assumption for isolated prototype; formal owner approval pending release |
| Compatibility and SQL | Legacy clients receive explicit upgrade-required behavior. Retire unrestricted network SQL. Do not provide SQL fallback. | Technical assumption for isolated prototype; formal owner approval pending release |
| Restore | Backup restore requires server-authorized epoch reset and reconciliation. | Technical assumption for isolated prototype; formal owner approval pending release |
| Financial baseline | Verified Indian branches use explicitly configured INR; support partial payments; payment/reversal events are immutable and idempotent; reversal total cannot exceed original payment; do not fabricate historical events; reconcile history only from verified financial records; preserve current invoice numbering pending Finance's final policy. | Not included in approval; Finance confirmation pending |
| Identity/data | Preserve integer IDs and stable UUID mappings; never match solely by integer IDs; use synthetic development fixtures; actual Edge ownership reconciliation is a rollout prerequisite. | Not included in approval; data-owner confirmation pending |
| API ownership | Classify Spring routes as Edge operational, cloud administration, public guest, read-only, or sync-only; block direct cloud operational writes in Edge-authoritative mode. | Approved for development; API-owner authority coverage unverified |

## 2. Explicitly approved development decisions

The user explicitly authorized isolated prototype development on 2026-11-24. The record below is an execution authorization only. It does not assign the approver authority over formal owner policies.

| Decision / scope | Approver and role | Decision | Date | Evidence / conditions |
|---|---|---|---|---|
| Isolated Phase 3A prototype execution using proposed policies as assumptions | DineMaster; Senior developer | Authorized for development only | 2026-11-24 | User request authorizes local test databases, synthetic fixtures, and development-only credentials. This is not a formal owner-policy approval; no production scope. |

## 3. Human confirmations required before Phase 3A coding

Formal owner approvals remain pending and are release gates. For isolated prototype work, use the listed policies as assumptions, as explicitly authorized by DineMaster. Do not infer formal owner authority from job title or mark any owner approval as granted.

### Formal owner approvals (release gates; not blockers for isolated prototype work)

1. **Security owner — pending release gate:** Approve RS256 key custody/rotation; token separation/claims and 15-minute, 7-day, and 12-hour limits; 15-minute activation codes; Argon2id and OS storage; HTTPS/WSS trust; expiry/revocation; and epoch rejection.
2. **Product owner — pending release gate:** Approve one host per branch; no independent writes during host outage; signed-grant-only offline operation; guest QR scope; Flutter Web restrictions; and legacy-client upgrade behavior.
3. **Operations owner — pending release gate:** Approve enrollment, host replacement/rebind/fencing, credential revocation, offline outage behavior, and restore quarantine/reset/reconciliation.
4. **API owner — pending release gate:** Approve route ownership classification and denial of direct operational writes. Actual route inventory/classification remains implementation work.
5. **Client owner — pending release gate:** Approve the upgrade-required response, minimum-client policy, and client migration behavior with no SQL fallback.
6. **Finance, data, privacy, and other domain owners — pending as applicable:** Approve financial, historical reconciliation, customer/PII, inventory, and ownership policies before their relevant business or production workflows.

### Decisions safely deferred from Phase 3A coding

These do not authorize Phase 3B or change the security boundary. Resolve them before implementing the relevant business workflows:

- Finance: partial reversal rules, rounding, tips/fees, refund approval thresholds, audit retention, and final invoice numbering. The development baseline preserves current invoice numbering and forbids fabricated history.
- Finance/Data owners: actual historical payment reconciliation from verified records. No production records are to be inspected during this development phase.
- Product/Data owners: customer/loyalty ownership, customer merge and PII rules; inventory movement types, units, negative-stock behavior, and adjustment permissions.
- Data owners/Operations: actual Edge host, tenant, branch, and UUID ownership verification. Use synthetic fixtures in development; perform real reconciliation only as a production rollout gate.
- Backend/Operations: confirm the deployed PostgreSQL and Flyway support matrix before production deployment. This is not a reason to test against production during development.

## 4. Production rollout prerequisites (separate authorization)

These are not development approvals and remain mandatory before production rollout:

- ☐ Inventory and back up actual Edge hosts/databases; verify schema drift and branch ownership.
- ☐ Reconcile real UUID ownership from verified source evidence; never infer correspondence from matching integer IDs.
- ☐ Provision production signing keys/certificates under approved custody; rotate/revoke and recover them through rehearsed procedures.
- ☐ Validate TLS trust, certificate renewal, and supported client platforms in the actual deployment environment.
- ☐ Confirm deployed PostgreSQL/Flyway compatibility and rehearse migrations only on approved copies first.
- ☐ Rehearse host outage/replacement, cloud outage, expired grants, revocation, clock rollback, branch rebind, restore, and legacy-client rejection.
- ☐ Verify monitoring, support workflows, recovery, and an approved client rollout/rejection schedule.
- ☐ Obtain a named release owner's production authorization. Development approval does not grant it.
- ☐ Keep automatic synchronization disabled until its own implementation, validation, and release approvals are complete.

## 5. Approval recording format

For each decision, record:

```text
Decision ID / scope:
Decision: Approved | Rejected | Needs revision
Approver name and role:
Date:
Conditions or rationale:
Evidence/reference:
```

A blanket “Phase 3 approved” is insufficient for security, branch ownership, payment, or client compatibility decisions. If any mandatory decision is rejected or revised, update the design/API contract and obtain review of the changed policy before coding it.

## 6. Readiness

- **Phase 3A design:** Available for review.
- **Isolated prototype implementation:** Authorized by DineMaster on 2026-11-24 under the development-only scope above; assumptions must remain disabled by default and fail closed.
- **Formal policy approval / production release:** **PENDING** — Security, Product, Operations, API-owner, client-owner, Finance, data, privacy, and other applicable owner approvals remain release gates.
- **Phase 3B / Phase 4:** Not authorized by this checklist; remain out of scope.
- **Production rollout:** **NOT READY** — all prerequisites in Section 4 remain separate release gates.
