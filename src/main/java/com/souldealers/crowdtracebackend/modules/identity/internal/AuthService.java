package com.souldealers.crowdtracebackend.modules.identity.internal;

import com.souldealers.crowdtracebackend.modules.identity.*;
import com.souldealers.crowdtracebackend.shared.GenericResponseMessage;

public interface AuthService {
    GenericResponseMessage signUp(SignUpRequest signUpRequest);

    LoginResponse login(LoginRequest request);

    GenericResponseMessage logout(String authorizationHeader);

    GenericResponseMessage verifyOtp (VerifyOtpDto request);

    GenericResponseMessage resendOtp (ResendOtpRequest request);

    GenericResponseMessage resetPasswordRequest(PasswordResetRequest request);

    GenericResponseMessage resetPassword(PasswordReset request);
}
