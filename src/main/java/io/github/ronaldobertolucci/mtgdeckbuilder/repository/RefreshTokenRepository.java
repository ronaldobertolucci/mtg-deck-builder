package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.RefreshToken;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    @Modifying
    @Query("delete from RefreshToken t where t.sessionId in (select s.id from RefreshSession s where s.expiresAt <= :now)")
    void deleteExpired(Instant now);
}
