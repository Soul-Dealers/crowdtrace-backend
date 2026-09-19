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


}