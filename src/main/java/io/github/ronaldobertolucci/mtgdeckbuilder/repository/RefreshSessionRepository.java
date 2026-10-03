package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;
import java.time.Instant;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> lockById(UUID id);

    @Modifying
    @Query("delete from RefreshSession s where s.expiresAt <= :now")
    void deleteExpired(Instant now);

    @Modifying
    @Query("update RefreshSession s set s.revoked = true where s.user.id = :userId")
    void revokeByUserId(Long userId);
}
