package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

/** The identity-keyed controls, each mapped to a policy in {@code rate-limit.policies}. */
public enum IdentityAction {

    /** Failed authentication attempts against one address. */
    LOGIN("identity-login"),

    /** Outbound OTP emails for one address and purpose. Never refunded. */
    OTP_SEND("identity-otp-send"),

    /** OTP guesses for one address and purpose. */
    OTP_ATTEMPT("identity-otp-attempt");

    private final String policyName;

    IdentityAction(String policyName) {
        this.policyName = policyName;
    }

    public String policyName() {
        return policyName;
    }
}
