# Purpose-specific OTP emails

## Goal

Make account-verification and password-reset OTP emails communicate the action the recipient must take, while preserving a personal greeting.

## Design

The notification service will receive the recipient's real display name and the OTP purpose (`CREATE` or `RESET`). It will select a purpose-specific subject and HTML template.

| OTP purpose | Subject | Heading | Main instruction |
| --- | --- | --- | --- |
| `CREATE` | Verify your CrowdTrace account | Verify your email | Use the code to verify the account. |
| `RESET` | Reset your CrowdTrace password | Reset your password | Use the code to reset the password. |

Both templates will render `userName` and `otpCode`. AuthService will obtain the display name from the existing or newly created user rather than passing a UI label such as `Create Account` or `Reset Password` as a name.

## Error handling

Email delivery remains asynchronous and non-blocking as it is today. This change only improves the intended message content; it does not alter OTP generation, expiration, or delivery failure handling.

## Tests

Tests will assert that both classpath templates exist and expose the required Thymeleaf variables, and that the notification service selects the appropriate subject/template for each `OtpType`.
