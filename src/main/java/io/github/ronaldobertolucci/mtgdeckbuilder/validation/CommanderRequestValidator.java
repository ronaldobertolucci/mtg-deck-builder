package io.github.ronaldobertolucci.mtgdeckbuilder.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.CreateDeckRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;

public class CommanderRequestValidator implements ConstraintValidator<ValidCommander, CreateDeckRequest> {
    public boolean isValid(CreateDeckRequest value, ConstraintValidatorContext context) {
        if (value == null) return true;
        String field = "commanderOracleIds";
        String message = null;
        var ids = value.commanderOracleIds() == null ? java.util.List.<java.util.UUID>of() : value.commanderOracleIds();
        if (value.format() == Format.COMMANDER && ids.isEmpty()) {
            message = "Commander format requires one or two commander oracle IDs";
        } else if (new HashSet<>(ids).size() != ids.size()) {
            message = "Commander oracle IDs must be distinct";
        } else if (value.format() != null && value.format() != Format.COMMANDER && !ids.isEmpty()) {
            message = "Only COMMANDER format accepts commanders";
        }
        if (message == null) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
