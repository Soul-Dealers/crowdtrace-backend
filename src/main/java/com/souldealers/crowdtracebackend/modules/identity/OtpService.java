package com.souldealers.crowdtracebackend.modules.identity;


import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.shared.OtpType;

public interface OtpService {

     Otp generateOtp(String email, OtpType type);

     boolean consumeOtp(String otpCode, String email, OtpType type);

     boolean isOtpValid(String otpCode, String email, OtpType type);

     void invalidateOtp(String otpCode, String email, OtpType type);
}
