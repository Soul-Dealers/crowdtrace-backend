# Verify OTP Flow Design

## Goal

Provide public email-OTP verification for new CrowdTrace accounts.

## Contract

`POST /api/v1/auth/verify-otp` accepts an email address and a six-digit OTP. The
endpoint returns the existing API response envelope containing a success message.

## Flow

1. Validate and normalize the request email.
2. Load the account and require `PENDING_VERIFICATION` status.
3. Validate a current OTP for the `CREATE` purpose.
4. Atomically mark the account `ACTIVE` and invalidate the OTP.
5. Send the welcome notification and return `Email verified successfully`.

## Failures

- A missing, expired, or non-matching OTP is a validation failure.
- An account that is no longer pending verification is a conflict.
- OTP validation is purpose-aware, so non-registration codes cannot activate an account.

## Verification

Tests cover successful activation and OTP consumption, invalid OTP rejection, and
the public endpoint contract.
