package io.github.ronaldobertolucci.mtgdeckbuilder.validation;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CommanderRequestValidator.class)
public @interface ValidCommander {
    String message() default "Commander oracle ID is required for COMMANDER format";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
