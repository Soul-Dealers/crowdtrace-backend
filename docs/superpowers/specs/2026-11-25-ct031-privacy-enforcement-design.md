# CT-031 Privacy Enforcement — Design

**Task:** `docs/tasks/phase_6_privacy.md` § CT-031
**Status:** Approved approach (2026-11-25); deliberated with Codex (gpt-6.1-sol).

## Intent

Prove and close the gaps in the privacy rules CrowdTrace already has: consent before sensitive intake,
server-side public/user/admin boundaries, no sensitive values in ordinary logs, and an audit trail for
protected access. Give counsel a short index of where each rule lives.

## What already holds (verify, don't rebuild)

- Consent: `ConsentInput` requires `accepted=true`; `CaseSubmissionServiceImpl:42` rejects a version other
  than `crowdtrace.consent.sensitive-data-version`; a `CaseConsent` row is stored.
- Projections: `CaseMapper` has public/reporter/admin mappers; `PublicApiLeakTest` (CT-024) walks public JSON.
- Private report download is audited (`CASE_FILE.PRIVATE_ACCESS_GRANTED`).
- Takedown, restore and audit corrections are audit events.

## Goals

1. **Audit moderator reads of sensitive case data.** Each successful `GET` review case detail
   (`ReviewServiceImpl.getCaseDetail`, the only caller of `getAdminCase`) appends one audit row: new action
   `CASE.SENSITIVE_DETAILS_VIEWED`, target the case, actor the moderator, no sensitive values in metadata.
   The audit commits before the response is returned; if recording fails, the details are not returned.
   The current read is a `READ ONLY` repeatable-read snapshot and the recorder refuses read-only
   transactions, so the audit is written outside that snapshot (for example by the controller-facing
   service calling the snapshot read, then the recorder in its own write transaction).
2. **Remove sensitive values from logs.** `ResendNotificationService` (lines ~38, 48, 70, 72) logs raw
   recipient emails and the provider's exception message. Log event type and outcome only, with the
   exception class instead of its message.
3. **Log-redaction test.** Drive the email paths (success and provider failure) with distinctive sentinel
   values (email, OTP, medical text embedded in the provider error) and assert none appear in captured output.
4. **Real-route authorization matrix.** Enumerate every handler from `RequestMappingHandlerMapping`; fail if a
   route has no entry in an explicit, hand-written policy table (public / registered / moderator /
   super-admin). Assert guests get 401 on every non-public route and each role gets the expected 401/403/2xx
   boundary. Bodies must be valid enough that validation can't mask a missing authorization check. Extend,
   don't duplicate, `ReviewEndpointTest` and `CaseFileAccessEndpointTest`.
5. **Consent-version and projection tests** where coverage is missing: outdated or missing consent version
   rejected and no case/consent row written; reporter and admin projections each checked against their own
   allowlist the way CT-024 did for public.
6. **Legal evidence index** (`docs/privacy/policy-references.md`): consent version → notice, data boundaries,
   access auditing, logging rules, retention ownership (CT-032), and the open counsel questions
   (product spec §7.2, open question 6). Inputs for review, not legal conclusions.

## Non-goals

- Moving consent, mapping or redaction code into `modules/privacy`. Ownership stays where it is.
- A central Logback masking layer. Pattern matching can't recognise medical prose; call-site fixes plus the
  sentinel test cover the criterion.
- Auditing reporters reading their own case (Edem: not important).
- Changing upload order. Report upload before consent follows the product spec (steps 4 → 5); the gap is
  recorded in the legal index and cleaned up by the phase-6 stale-upload sweep.
- Deduplicating or expiring the new audit rows. Audit is append-only.

## Approaches considered

| Approach | Trade-off | Verdict |
|---|---|---|
| Verification plus targeted fixes in place | Small diff, matches CT-024 | **Chosen** |
| Centralise policy code in `modules/privacy` | Large move, no behaviour gained | Rejected |
| Central log masking plus call-site fixes | Extra moving part; misses free text anyway | Rejected |

Audit granularity: one row per moderator detail load (chosen) vs. sampling/deduping (rejected: can't prove
who saw a given case).

## Left to the builder

- Exact transaction split for goal 1, as long as audit-before-disclosure and the review snapshot both hold.
- Whether the policy table for goal 4 lives in the test or a test resource.
- Confirm the new audit action needs no migration (no DB check on action codes was found).
