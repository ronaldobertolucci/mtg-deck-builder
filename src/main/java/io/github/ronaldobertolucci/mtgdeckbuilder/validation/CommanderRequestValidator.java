package io.github.ronaldobertolucci.mtgdeckbuilder.validation;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.CreateDeckRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
public class CommanderRequestValidator implements ConstraintValidator<ValidCommander, CreateDeckRequest> {
    public boolean isValid(CreateDeckRequest value, ConstraintValidatorContext context) {
        if (value == null || value.format() != Format.COMMANDER || value.commanderOracleId() != null) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("commanderOracleId").addConstraintViolation();
        return false;
    }
}
