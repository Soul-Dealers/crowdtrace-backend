package com.souldealers.crowdtracebackend.modules.identity.internal;

import com.souldealers.crowdtracebackend.modules.identity.SignUpRequest;
import com.souldealers.crowdtracebackend.shared.GenericMessageResponse;

public interface AuthService {
    GenericMessageResponse signUp(SignUpRequest signUpRequest);

}
