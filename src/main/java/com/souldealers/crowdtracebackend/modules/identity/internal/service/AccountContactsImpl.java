package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.AccountContacts;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountContactsImpl implements AccountContacts {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> emailOf(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> user.getAccountStatus() == UserStatus.ACTIVE && user.getDeletedAt() == null)
                .map(User::getEmail);
    }
}
