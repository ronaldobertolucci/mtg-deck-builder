package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.exception.ErrorResponseDto;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @InjectMocks
    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    void handleEntityNotFound_ShouldReturnNotFound() {
        // Arrange
        EntityNotFoundException exception = new EntityNotFoundException("User not found");

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleEntityNotFound(exception, request);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("User not found", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    void handleIllegalArgument_ShouldReturnBadRequest() {
        // Arrange
        IllegalArgumentException exception = new IllegalArgumentException("Invalid input");

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleIllegalArgument(exception, request);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid input", response.getBody().message());
    }

    @Test
    void handleValidationErrors_ShouldReturnBadRequestWithDetails() {
        // Arrange
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError1 = new FieldError("user", "email", "must be valid");
        FieldError fieldError2 = new FieldError("user", "password", "must not be blank");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError1, fieldError2));

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(null, bindingResult);

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleValidationErrors(exception, request);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Validation Failed", response.getBody().error());
        assertNotNull(response.getBody().details());
        assertEquals(2, response.getBody().details().size());
    }

    @Test
    void handleAuthenticationErrors_WithBadCredentials_ShouldReturnUnauthorized() {
        // Arrange
        BadCredentialsException exception = new BadCredentialsException("Bad credentials");

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleAuthenticationErrors(exception, request);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid email or password", response.getBody().message());
    }

    @Test
    void handleAuthenticationErrors_WithUsernameNotFound_ShouldReturnUnauthorized() {
        // Arrange
        UsernameNotFoundException exception = new UsernameNotFoundException("User not found");

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleAuthenticationErrors(exception, request);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid email or password", response.getBody().message());
    }

    @Test
    void handleRuntimeException_WithJWTError_ShouldReturnUnauthorized() {
        // Arrange
        RuntimeException exception = new RuntimeException("Invalid JWT token");

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleRuntimeException(exception, request);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid or expired token", response.getBody().message());
    }

    @Test
    void handleRuntimeException_WithGenericError_ShouldReturnInternalServerError() {
        // Arrange
        RuntimeException exception = new RuntimeException("Something went wrong");

        // Act
        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleRuntimeException(exception, request);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}