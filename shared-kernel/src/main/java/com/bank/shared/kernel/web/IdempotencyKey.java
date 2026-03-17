package com.bank.shared.kernel.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

@Documented
@Constraint(validatedBy = IdempotencyKey.IdempotencyKeyValidator.class)
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKey {
    String message() default "Invalid idempotency key";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class IdempotencyKeyValidator implements ConstraintValidator<IdempotencyKey, String> {
        private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9-_]{16,64}$");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value != null && IDEMPOTENCY_KEY_PATTERN.matcher(value).matches();
        }
    }
}
