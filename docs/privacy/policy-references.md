# Privacy Policy References (for legal review)

**Status:** CT-031, 2026-11-25. This is an evidence index for counsel: where each privacy rule is enforced and
which test proves it. It is input for review, not a legal conclusion. Final wording and obligations under Ghana's
Data Protection Act, 2012 (Act 843) are for counsel and the Data Protection Commission
([product spec §7](../product-spec.md#7-data-retention--legal-compliance), §7.2, and open question 6 in §14).

Paths are relative to `src/main/java/com/souldealers/crowdtracebackend/` (code) and
`src/test/java/com/souldealers/crowdtracebackend/` (tests).

## Rules

| Rule | Enforced in | Proven by | Open question for counsel |
|---|---|---|---|
| **Consent before sensitive intake.** A case is created only with `accepted=true` and the current consent version; a consent row (version, source, time) is stored with the case. | `modules/casefile/CaseSubmissionRequest.java` (`ConsentInput`); `modules/casefile/internal/CaseSubmissionServiceImpl.java:43` (version check), `:60` (consent row). Version: `crowdtrace.consent.sensitive-data-version` in `application.yaml:52`, default `v1`. | `CaseSubmissionEndpointTest` (refused and outdated consent write no case), `CaseSubmissionServiceTest`, `CaseSubmissionRequestValidationTest` | Version `v1` is not yet tied to approved notice text. Which wording does `v1` mean, and what must the consent record hold (spec open question 6)? |
| **Public, reporter and admin views are separate.** The public sees only public fields; reporters see their own case with its sensitive details; moderators see everything except storage keys. | `modules/casefile/internal/CaseMapper.java:27` (`toPublic`), `:44` (`toReporter`), `:62` (`toAdmin`) | `CaseProjectionContractTest` (exact field lists for each view), `CaseLifecycleEndpointTest` (another user's case returns 404) | Is the field split in product spec §5.1 the agreed minimum for each audience? |
| **Route access is checked server-side.** Every endpoint has a declared access level: public, signed-in, registered, moderator, or Super Admin. | `modules/identity/SecurityConfig.java` (public list, everything else requires sign-in); `@Requires*` annotations on each handler | `RouteAuthorizationMatrixTest` (each real route against a policy table taken from the spec) | None. |
| **Private report downloads are audited.** Each signed link issued for a case's evidence writes `CASE_FILE.PRIVATE_ACCESS_GRANTED` (moderator, file, case). | `modules/casefile/internal/CaseFileAccessServiceImpl.java:71-78` | `CaseFileAccessEndpointTest` | How long must access records be kept? |
| **Moderator views of sensitive details are audited.** Each review-detail load writes `CASE.SENSITIVE_DETAILS_VIEWED` (moderator, case) before the details are returned. If the audit fails, nothing is shown. Reporters reading their own case are not audited. | `modules/governance/internal/ReviewServiceImpl.java:68-79`; `shared/audit/AuditAction.java:32` | `ReviewDetailAuditTest`, `ReviewAuditFailureTest.aFailedAuditReturnsNoDetail` | Is per-view auditing of staff access enough, or is a reason-for-access required? |
| **Audit records are append-only.** The database rejects UPDATE, DELETE and TRUNCATE on `audit_events`. Mistakes are fixed by adding a compensating event. Audit metadata holds ids and codes only, never sensitive values. | `src/main/resources/db/migration/V15__make_audit_events_append_only.sql`; `shared/audit/AuditAction.java` (metadata schemas) | `shared/audit` tests | None. |
| **Sensitive values stay out of ordinary logs.** Email logging records the event and the exception type, never the recipient, the OTP, or the provider's error text, which can echo them back. | `shared/ResendNotificationService.java:38, 48, 70, 73` | `ResendNotificationLoggingTest` (sentinel email, OTP and medical text never appear in captured output) | None. |
| **Retention.** Twelve months after closure, sensitive fields and private files are deleted and the deletion is audited (product spec §7.1, §7.3). | Not built yet: CT-032 / CT-033 | — | Confirm the 12-month window and the archive rule for public historical fields. |

## Known gap: report upload happens before consent

The product spec's submission flow (§3.2) has the reporter upload the mandatory report (step 4) **before**
consenting to sensitive-data collection (step 5). The backend follows that order: `POST /api/user/case-files`
stores the file before the case submission that carries the consent. A file uploaded by someone who then
abandons the form stays in private storage with no consent record. It is never public and never linked to a case.

No scheduled cleanup of such unattached uploads exists yet. Counsel's call: is pre-consent storage of an uploaded
report acceptable if it is deleted within a set time, or must consent move before upload?
