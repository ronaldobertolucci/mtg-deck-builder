package io.github.ronaldobertolucci.mtgdeckbuilder.model.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "refresh_sessions")
@Getter @Setter @NoArgsConstructor
public class RefreshSession {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean revoked;
    @Column(nullable = false, length = 64) private String currentHash;
}
