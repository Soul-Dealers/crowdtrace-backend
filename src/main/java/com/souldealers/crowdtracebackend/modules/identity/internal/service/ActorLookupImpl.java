package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActorLookupImpl implements ActorLookup {

    private static final String UNAUTHORIZED_MSG = "Authentication required";

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public ActorView byEmail(String email) {
        User user = userRepository.findByEmail(email)
                .filter(candidate -> candidate.getAccountStatus() == UserStatus.ACTIVE
                        && candidate.getDeletedAt() == null)
                .orElseThrow(() -> new UnauthorizedException(UNAUTHORIZED_MSG));
        return new ActorView(user.getId(), user.getRole());
    }
}
