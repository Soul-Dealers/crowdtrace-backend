# Identity Email OTP Verification Design

## Goal

Complete CrowdTrace's account-verification flow without implementing password reset: users sign up as pending, receive a numeric email OTP, verify it to activate their account, and can request a replacement OTP.

## API Contract

All endpoints live under `/api/v1/auth` and are public:

| Endpoint | Responsibility | Successful response |
| --- | --- | --- |
| `POST /signup` | Create a pending account and send its verification OTP; resend one when the email already belongs to a pending account. | 200, generic token-sent message |
| `POST /verify-otp` | Validate the supplied email and `CREATE` OTP, activate the pending account, and consume the OTP. | 200, generic verification-success message |
| `POST /resend-otp` | Issue and email a replacement `CREATE` OTP for a pending account. | 200, generic token-sent message |
| `POST /login` | Authenticate an already active account and return its JWT response. | 200, login response |

Password-reset routes and reset-specific service methods are deliberately out of scope.

## Responsibilities

`AuthController` exposes the HTTP contract and Springdoc annotations only. Public identity operations must not inherit the Basic-auth OpenAPI security requirement used by the protected user-list endpoint.

`AuthService` orchestrates user state transitions. It normalizes the email, creates a `PENDING_VERIFICATION` user during first signup, requests a `CREATE` OTP, and sends it through the notification service. On verification it requires the user to be pending, validates an unexpired OTP belonging to that email and type, activates the user, and invalidates the consumed OTP.

`OtpService` owns OTP creation and validation. It generates a six-character numeric code using only digits `1` through `9`; `0` must never appear. Validation is purpose-aware, checks ownership and expiry, and invalidation or replacement prevents prior codes from being reused.

## Error Handling

Verification and resend reject unknown users, non-pending accounts, absent OTPs, expired OTPs, OTPs belonging to another email, and OTPs with the wrong purpose through the existing application exception-response mechanism. Login remains unavailable to pending accounts through the existing account-status checks.

## Documentation and Tests

Springdoc annotations describe each public endpoint, request/response schema, success status, and expected error responses. The generated OpenAPI document must reflect the implemented `/api/v1/auth` paths rather than the old planned `/api/auth` contract.

Tests will prove that a new signup creates and emails a non-zero `CREATE` OTP, a pending account can be activated with its valid OTP, invalid or expired OTPs do not activate accounts, and resending replaces the valid code for a pending account.
