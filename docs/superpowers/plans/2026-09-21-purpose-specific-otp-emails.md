# Purpose-specific OTP Emails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send clearly labeled, personalized OTP emails for account verification and password reset.

**Architecture:** Keep `OtpType` as the source of email purpose. `AuthServiceImpl` passes the actual `User.displayName` and the purpose to the notification service; the service maps each purpose to a specific subject and Thymeleaf template. Templates contain purpose-specific heading and instructional copy, but share the existing `userName` and `otpCode` variable names.

**Tech Stack:** Java 21, Spring Boot, Thymeleaf, JUnit 5, AssertJ, Maven.

---

### Task 1: Define and prove the purpose-specific email contract

**Files:**
- Modify: `src/test/java/com/souldealers/crowdtracebackend/shared/ResendNotificationTemplateTest.java`
- Create: `src/main/resources/templates/password-reset-otp.html`

- [ ] **Step 1: Write failing template contract tests**

Add a test that loads `templates/password-reset-otp.html` and asserts it contains the required variables and reset-oriented copy:

```java
@Test
void keepsPasswordResetOtpMarkupInAClasspathTemplate() throws IOException {
    ClassPathResource template = new ClassPathResource("templates/password-reset-otp.html");

    assertThat(template.exists()).isTrue();
    assertThat(template.getContentAsString(StandardCharsets.UTF_8))
            .contains("Reset your password")
            .contains("th:text=\"${userName}\"")
            .contains("th:text=\"${otpCode}\"");
}
```

Rename the existing OTP test to state it verifies account-verification markup and assert it contains `Verify your email`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./mvnw test -q -Dtest=ResendNotificationTemplateTest`

Expected: FAIL because `password-reset-otp.html` does not exist.

- [ ] **Step 3: Create the reset template**

Create an HTML email based on `otp-verification.html` with these visible strings:

```html
<title>Reset your CrowdTrace password</title>
<h1>Reset your password</h1>
<p>Hi <span th:text="${userName}">there</span>, use this code to reset your CrowdTrace password:</p>
```

Retain the existing OTP display and five-minute safety copy.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `./mvnw test -q -Dtest=ResendNotificationTemplateTest`

Expected: PASS.

### Task 2: Select email content by OTP purpose and use actual names

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/NotificationService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/ResendNotificationService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/shared/ResendNotificationTemplateTest.java`

- [ ] **Step 1: Write failing sender-selection tests**

Expose package-private rendering helpers in `ResendNotificationService` and test that each purpose uses the correct content. The assertions must distinguish reset from verification:

```java
assertThat(service.otpEmailSubject(OtpType.CREATE))
        .isEqualTo("Verify your CrowdTrace account");
assertThat(service.otpEmailSubject(OtpType.RESET))
        .isEqualTo("Reset your CrowdTrace password");
assertThat(service.otpEmailTemplate(OtpType.CREATE))
        .isEqualTo("otp-verification");
assertThat(service.otpEmailTemplate(OtpType.RESET))
        .isEqualTo("password-reset-otp");
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./mvnw test -q -Dtest=ResendNotificationTemplateTest`

Expected: FAIL because the purpose-aware helpers and sender signature do not exist.

- [ ] **Step 3: Implement minimal purpose-aware delivery**

Change the notification-port method to:

```java
void sendOtpEmail(String to, String otpCode, String userName, OtpType type);
```

Make `ResendNotificationService` derive the subject and template from `type`, render the selected template with the real `userName`, and retain asynchronous, non-blocking delivery.

In `AuthServiceImpl`, change `generateAndSendOtp` to accept a `User` rather than a raw email, then call:

```java
notificationService.sendOtpEmail(
        user.getEmail(), otp.getCode(), user.getDisplayName(), type);
```

Use this helper for the newly saved sign-up user, an existing pending-verification user, resend OTP, and password reset request.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./mvnw test -q -Dtest=ResendNotificationTemplateTest`

Expected: PASS.

### Task 3: Verify the complete project remains healthy

**Files:**
- Verify only.

- [ ] **Step 1: Run the full Maven suite**

Run: `./mvnw test -q`

Expected: exit code `0`.

- [ ] **Step 2: Inspect the final diff**

Run: `git diff --check && git diff -- src/main/java/com/souldealers/crowdtracebackend/shared/NotificationService.java src/main/java/com/souldealers/crowdtracebackend/shared/ResendNotificationService.java src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java src/main/resources/templates src/test/java/com/souldealers/crowdtracebackend/shared/ResendNotificationTemplateTest.java`

Expected: no whitespace errors; only purpose-aware email selection, real display-name propagation, the reset template, and corresponding tests.
