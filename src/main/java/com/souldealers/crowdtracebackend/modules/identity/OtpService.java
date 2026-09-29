package com.souldealers.crowdtracebackend.modules.identity;


import com.souldealers.crowdtracebackend.shared.OtpType;

public interface OtpService {

     String generateOtp(String email, OtpType type);

     boolean consumeOtp(String otpCode, String email, OtpType type);

}
