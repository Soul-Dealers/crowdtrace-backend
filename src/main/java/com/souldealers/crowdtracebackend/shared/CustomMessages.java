package com.souldealers.crowdtracebackend.shared;

public class CustomMessages {
    private CustomMessages() {}

    public static final String RESOURCE_NOT_FOUND = "The requested resource not found";
    public static final String INTERNAL_SERVER_ERROR_MSG = "An unexpected error occurred";
    public static final String UNAUTHORIZED_MSG = "You are not authorized to perform this action";
    public static final String VALIDATION_FAILED_MSG = "One or more fields are invalid";
    public static final String JWT_EXC_MSG = "Could not authenticate you, try again";
    public static final String USER_NOT_FOUND_MSG = "User not found";
    public static final String EMAIL_NOT_NULL_MSG = "Email cannot be null";
    public static final String EXISTING_EMAIL = "This email is already registered";
    public static final String TOKEN_SENT_MSG = "Token Sent, check your email";
    public static final String VERIFICATION_SUCCESS_MSG = "Email verified successfully";
    public static final String OTP_VERIFICATION_FAILED_MSG = "Could not verify your OTP";
    public static final String UNSUPPORTED_OTP_TYPE_MSG = "Unsupported OTP type";
    public static final String PASSWORD_MISMATCH = "Passwords do not match";
    public static final String RESET_PASSWORD_SUCC = "Password reset successfully, log in";
    public static final String LOGOUT_SUCCESS_MSG = "Logged out successfully";


}
