package io.github.ronaldobertolucci.mtgdeckbuilder.model.security;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Table(name = "refresh_tokens")
@Getter @NoArgsConstructor @AllArgsConstructor
public class RefreshToken {
    @Id @Column(length = 64) private String hash;
    @Column(nullable = false) private UUID sessionId;
}
