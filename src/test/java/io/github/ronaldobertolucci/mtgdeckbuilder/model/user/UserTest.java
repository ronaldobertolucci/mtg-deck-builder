package io.github.ronaldobertolucci.mtgdeckbuilder.model.user;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    private User user;
    private Role userRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        userRole = new Role(1L, "USER");
        adminRole = new Role(2L, "ADMIN");

        user = User.builder()
                .id(1L)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .enabled(true)
                .roles(new HashSet<>(Set.of(userRole)))
                .build();
    }

    @Test
    void getAuthorities_WhenUserHasOneRole_ShouldReturnOneAuthority() {
        // Act
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();

        // Assert
        assertNotNull(authorities);
        assertEquals(1, authorities.size());
        assertTrue(authorities.stream()
                .anyMatch(auth -> auth.getAuthority().equals("USER")));
    }

    @Test
    void getAuthorities_WhenUserHasMultipleRoles_ShouldReturnMultipleAuthorities() {
        // Arrange
        user.getRoles().add(adminRole);

        // Act
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();

        // Assert
        assertEquals(2, authorities.size());
        assertTrue(authorities.stream()
                .anyMatch(auth -> auth.getAuthority().equals("USER")));
        assertTrue(authorities.stream()
                .anyMatch(auth -> auth.getAuthority().equals("ADMIN")));
    }

    @Test
    void getUsername_ShouldReturnEmail() {
        // Act
        String username = user.getUsername();

        // Assert
        assertEquals("john@example.com", username);
    }

    @Test
    void isAccountNonExpired_ShouldReturnTrue() {
        // Act & Assert
        assertTrue(user.isAccountNonExpired());
    }

    @Test
    void isAccountNonLocked_ShouldReturnTrue() {
        // Act & Assert
        assertTrue(user.isAccountNonLocked());
    }

    @Test
    void isCredentialsNonExpired_ShouldReturnTrue() {
        // Act & Assert
        assertTrue(user.isCredentialsNonExpired());
    }

    @Test
    void isEnabled_WhenEnabledIsTrue_ShouldReturnTrue() {
        // Act & Assert
        assertTrue(user.isEnabled());
    }

    @Test
    void isEnabled_WhenEnabledIsFalse_ShouldReturnFalse() {
        // Arrange
        user.setEnabled(false);

        // Act & Assert
        assertFalse(user.isEnabled());
    }
}