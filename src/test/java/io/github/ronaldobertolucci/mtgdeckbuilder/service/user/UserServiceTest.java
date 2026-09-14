package io.github.ronaldobertolucci.mtgdeckbuilder.service.user;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserRegistrationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.RoleRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailVerificationService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailVerificationService emailVerificationService;

    @InjectMocks
    private UserService userService;

    private User testUser;
    private Role userRole;
    private UserRegistrationDto registrationDto;

    @BeforeEach
    void setUp() {
        userRole = new Role(1L, "USER");

        testUser = User.builder()
                .id(1L)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .enabled(true)
                .roles(new HashSet<>(Set.of(userRole)))
                .build();

        registrationDto = new UserRegistrationDto(
                "John",
                "Doe",
                "john@example.com",
                LocalDate.of(1990, 1, 1),
                "password123"
        );
    }

    @Test
    void register_WhenEmailDoesNotExist_ShouldCreateUser() {
        // Arrange
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        doNothing().when(emailVerificationService).sendVerificationEmail(any());

        // Act
        UserDto result = userService.register(registrationDto);

        // Assert
        assertNotNull(result);
        assertEquals("john@example.com", result.email());
        assertEquals("John", result.firstName());
        assertEquals("Doe", result.lastName());
        assertFalse(result.enabled());

        // Verify password was encoded
        verify(passwordEncoder, times(1)).encode("password123");

        // Verify user was saved
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("encodedPassword", savedUser.getPassword());
    }

    @Test
    void register_WhenEmailAlreadyExists_ShouldThrowException() {
        // Arrange
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> userService.register(registrationDto));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_WhenUserRoleNotFound_ShouldThrowException() {
        // Arrange
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("USER")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RuntimeException.class,
                () -> userService.register(registrationDto));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void findById_WhenUserExists_ShouldReturnUserDto() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        UserDto result = userService.findById(1L);

        // Assert
        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("john@example.com", result.email());
        verify(userRepository, times(1)).findById(1L);
    }

    @Test
    void findById_WhenUserDoesNotExist_ShouldThrowException() {
        // Arrange
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(EntityNotFoundException.class,
                () -> userService.findById(999L));
    }

    @Test
    void disable_WhenUserExists_ShouldDisableUser() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        UserDto result = userService.disable(1L);

        // Assert
        assertNotNull(result);
        assertFalse(result.enabled());
        assertFalse(testUser.getEnabled());
        verify(userRepository, times(1)).findById(1L);
    }

    @Test
    void enable_WhenUserExists_ShouldEnableUser() {
        // Arrange
        testUser.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        UserDto result = userService.enable(1L);

        // Assert
        assertNotNull(result);
        assertTrue(result.enabled());
        assertTrue(testUser.getEnabled());
        verify(userRepository, times(1)).findById(1L);
    }

    @Test
    void delete_WhenUserExists_ShouldDeleteUser() {
        // Arrange
        when(userRepository.existsById(1L)).thenReturn(true);

        // Act
        userService.delete(1L);

        // Assert
        verify(userRepository, times(1)).deleteById(1L);
    }

    @Test
    void delete_WhenUserDoesNotExist_ShouldThrowException() {
        // Arrange
        when(userRepository.existsById(anyLong())).thenReturn(false);

        // Act & Assert
        assertThrows(EntityNotFoundException.class,
                () -> userService.delete(999L));

        verify(userRepository, never()).deleteById(anyLong());
    }
}