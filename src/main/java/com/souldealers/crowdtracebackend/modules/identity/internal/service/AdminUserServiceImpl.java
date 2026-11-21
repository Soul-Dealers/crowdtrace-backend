package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.AdminUserResponse;
import com.souldealers.crowdtracebackend.modules.identity.AdminUserService;
import com.souldealers.crowdtracebackend.modules.identity.RequiresSuperAdmin;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.AdminUserSpecification;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import com.souldealers.crowdtracebackend.shared.SeekCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {
    static final int MAX_SIZE = 50;

    private final UserRepository userRepository;

    @Override
    @RequiresSuperAdmin
    @Transactional(readOnly = true)
    public CursorResponse<AdminUserResponse> list(UserRoles role, UserStatus status, String q, String cursor,
                                                  int size) {
        int limit = Math.clamp(size, 1, MAX_SIZE);
        SeekCursor seek = cursor == null || cursor.isEmpty() ? null : SeekCursor.decode(cursor);
        List<User> fetched = userRepository.findBy(AdminUserSpecification.matching(role, status, q, seek),
                fluent -> fluent.sortBy(AdminUserSpecification.ORDER).limit(limit + 1).all());
        boolean hasNext = fetched.size() > limit;
        List<User> page = hasNext ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasNext ? new SeekCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new CursorResponse<>(page.stream().map(AdminUserServiceImpl::toResponse).toList(), limit, hasNext,
                nextCursor);
    }

    static AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getDisplayName(), user.getEmail(), user.getRole(),
                user.getAccountStatus(), user.getCreatedAt());
    }
}
