package io.github.ronaldobertolucci.mtgdeckbuilder.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.controller.DeckController;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationLanguageTest {
    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of(new CreateDeckRequest("", null, null),
                        List.of("Deck name is required", "Format is required")),
                Arguments.of(new CreateDeckRequest("Deck", Format.COMMANDER, Arrays.asList((UUID) null)),
                        List.of("Commander oracle ID is required")),
                Arguments.of(new CreateDeckRequest("Deck", Format.COMMANDER,
                                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())),
                        List.of("At most two commander oracle IDs are allowed")),
                Arguments.of(new ImportDeckRequest("", null, ""),
                        List.of("Deck name is required", "Format is required", "Deck import text is required")),
                Arguments.of(new RenameDeckRequest(""), List.of("Deck name is required")),
                Arguments.of(new RenameDeckRequest("x".repeat(256)),
                        List.of("Deck name must not exceed 255 characters")),
                Arguments.of(new UpsertDeckCardRequest(null, null, null),
                        List.of("Oracle ID is required", "Board type is required", "Quantity is required")),
                Arguments.of(new UpsertDeckCardRequest(null, null, -1),
                        List.of("Oracle ID is required", "Board type is required", "Quantity must be zero or greater")),
                Arguments.of(new PasswordResetTokenForgotDto(""), List.of("Email is required")),
                Arguments.of(new PasswordResetTokenForgotDto("invalid"), List.of("Email must be valid")),
                Arguments.of(new PasswordResetTokenResetDto("", ""),
                        List.of("Token is required", "Password is required", "Password must have at least 8 characters")),
                Arguments.of(new ResendVerificationDto(""), List.of("Email is required")),
                Arguments.of(new ResendVerificationDto("invalid"), List.of("Email must be valid"))
        );
    }

    @ParameterizedTest
    @MethodSource("requests")
    void requestErrorsRemainEnglishWithPortugueseLocale(Object request, List<String> expectedMessages) {
        var previous = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(Locale.forLanguageTag("pt-BR"));
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            assertThat(validator.validate(request)).extracting(ConstraintViolation::getMessage)
                    .containsExactlyInAnyOrderElementsOf(expectedMessages);
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }

    @Test
    void paginationErrorsRemainEnglishWithPortugueseLocale() throws Exception {
        var previous = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(Locale.forLanguageTag("pt-BR"));
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var controller = new DeckController(null, null, null, null);
            var method = DeckController.class.getMethod("list", User.class, int.class, int.class);
            assertThat(validator.forExecutables().validateParameters(controller, method, new Object[]{null, -1, 0}))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactlyInAnyOrder("Page must be zero or greater", "Page size must be at least 1");
            assertThat(validator.forExecutables().validateParameters(controller, method, new Object[]{null, 0, 101}))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("Page size must not exceed 100");
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }
}
