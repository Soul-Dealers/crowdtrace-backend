package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.AdminUserResponse;
import com.souldealers.crowdtracebackend.modules.identity.AdminUserService;
import com.souldealers.crowdtracebackend.modules.identity.RequiresSuperAdmin;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.AdminUserSpecification;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.SeekCursor;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {
    static final int MAX_SIZE = 50;

    private final UserRepository userRepository;
    private final AuditRecorder auditRecorder;

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

    @Override
    @RequiresSuperAdmin
    @Transactional
    @AuditedOperation(AuditAction.USER_ROLE_CHANGED)
    public AdminUserResponse changeRole(String actorEmail, long userId, UserRoles role) {
        if (role == UserRoles.SUPER_ADMIN) {
            throw new ValidationException("role must be REGISTERED_USER or MODERATOR");
        }
        User actor = actor(actorEmail);
        User target = lockManageableTarget(actor, userId);
        if (target.getAccountStatus() != UserStatus.ACTIVE) {
            throw new ConflictException("Only active accounts can change role");
        }
        UserRoles from = target.getRole();
        if (from == role) {
            throw new ConflictException("User already has this role");
        }
        target.setRole(role);
        auditRecorder.record(AuditRoleMapper.actor(actor), AuditAction.USER_ROLE_CHANGED, userId,
                AuditMetadata.of(AuditAction.USER_ROLE_CHANGED)
                        .put("fromRole", AuditRoleMapper.actorRole(from))
                        .put("toRole", AuditRoleMapper.actorRole(role))
                        .build());
        return toResponse(userRepository.saveAndFlush(target));
    }

    @Override
    @RequiresSuperAdmin
    @Transactional
    @AuditedOperation(AuditAction.USER_DEACTIVATED)
    public AdminUserResponse deactivate(String actorEmail, long userId) {
        User actor = actor(actorEmail);
        User target = lockManageableTarget(actor, userId);
        if (target.getAccountStatus() == UserStatus.DEACTIVATED) {
            throw new ConflictException("User is already deactivated");
        }
        target.setAccountStatus(UserStatus.DEACTIVATED);
        target.setCredentialsVersion(target.getCredentialsVersion() + 1);
        auditRecorder.record(AuditRoleMapper.actor(actor), AuditAction.USER_DEACTIVATED, userId,
                AuditMetadata.none(AuditAction.USER_DEACTIVATED));
        return toResponse(userRepository.saveAndFlush(target));
    }

    private User actor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new AccessDeniedException("Unknown actor"));
    }

    /** Locks the target row first, so two Super Admins acting at once see each other's change. */
    private User lockManageableTarget(User actor, long userId) {
        User target = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (target.getId().equals(actor.getId()) || target.getRole() == UserRoles.SUPER_ADMIN) {
            throw new AccessDeniedException("Super Admins cannot manage themselves or other Super Admins");
        }
        return target;
    }

    static AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getDisplayName(), user.getEmail(), user.getRole(),
                user.getAccountStatus(), user.getCreatedAt());
    }
}
