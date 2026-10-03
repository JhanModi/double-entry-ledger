package io.github.jhanmodi.ledger.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A string of at most {@code value} characters, counted the way Postgres counts them (Unicode code points). The
 * standard {@code @Size} counts Java's UTF-16 units, so an emoji would count as two, and the API would reject text that
 * the domain and the database both accept. Null is valid; combine with {@code @NotNull} or {@code @NotBlank} if needed.
 */
@Documented
@Constraint(validatedBy = MaxCharacters.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxCharacters {

    int value();

    String message() default "must be at most {value} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<MaxCharacters, String> {

        private int max;

        @Override
        public void initialize(MaxCharacters annotation) {
            max = annotation.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.codePointCount(0, value.length()) <= max;
        }
    }
}
