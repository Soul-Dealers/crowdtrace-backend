package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import jakarta.persistence.LockModeType;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @NullMarked
    Page<User> findAll(Pageable pageable);

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    Optional<User> findByEmailAndAccountStatus(String email, UserStatus accountStatus);

    /**
     * Sets the badge with a bulk update rather than mutating an entity: the decision
     * query clears the persistence context, so a previously loaded {@link User} may be
     * detached by the time this runs. Clears the context so no stale copy is served.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.badgeType = :badgeType where u.id = :id")
    int setBadgeType(@Param("id") Long id, @Param("badgeType") VerificationType badgeType);

    /**
     * Clears the badge only while it still holds {@code badgeType}, so a revocation can
     * never wipe a badge that belongs to a different request.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.badgeType = null where u.id = :id and u.badgeType = :badgeType")
    int clearBadgeType(@Param("id") Long id, @Param("badgeType") VerificationType badgeType);

}
