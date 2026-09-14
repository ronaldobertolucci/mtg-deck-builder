package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("USER role not found"));

        testUser = User.builder()
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
    void findByEmailWithRoles_WhenUserExists_ShouldReturnUserWithRoles() {
        // Arrange
        User savedUser = userRepository.save(testUser);
        entityManager.flush();
        entityManager.clear();

        // Act
        Optional<User> result = userRepository.findByEmailWithRoles("john@example.com");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("john@example.com", result.get().getEmail());
        assertFalse(result.get().getRoles().isEmpty());
        assertEquals(1, result.get().getRoles().size());
    }

    @Test
    void findByEmailWithRoles_WhenUserDoesNotExist_ShouldReturnEmpty() {
        // Act
        Optional<User> result = userRepository.findByEmailWithRoles("nonexistent@example.com");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void existsByEmail_WhenUserExists_ShouldReturnTrue() {
        // Arrange
        userRepository.save(testUser);

        // Act
        boolean exists = userRepository.existsByEmail("john@example.com");

        // Assert
        assertTrue(exists);
    }

    @Test
    void existsByEmail_WhenUserDoesNotExist_ShouldReturnFalse() {
        // Act
        boolean exists = userRepository.existsByEmail("nonexistent@example.com");

        // Assert
        assertFalse(exists);
    }

    @Test
    void findByIdWithRoles_WhenUserExists_ShouldReturnUserWithRoles() {
        // Arrange
        User savedUser = userRepository.save(testUser);
        entityManager.flush();
        entityManager.clear();

        // Act
        Optional<User> result = userRepository.findByIdWithRoles(savedUser.getId());

        // Assert
        assertTrue(result.isPresent());
        assertEquals(savedUser.getId(), result.get().getId());
        assertFalse(result.get().getRoles().isEmpty());
    }
}