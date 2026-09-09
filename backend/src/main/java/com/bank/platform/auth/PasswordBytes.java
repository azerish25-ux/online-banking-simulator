package com.bank.platform.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt truncates at 72 BYTES, not 72 characters: an emoji password of 30
 * characters is 120 bytes and two distinct such passwords can silently share
 * one stored hash after truncation. @Size caps characters, so the byte ceiling
 * needs its own validator.
 */
@Documented
@Constraint(validatedBy = PasswordBytes.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordBytes {

  int max() default 72;

  String message() default "password is longer than BCrypt's 72-byte limit";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<PasswordBytes, String> {

    private int max;

    @Override
    public void initialize(PasswordBytes annotation) {
      this.max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      if (value == null) {
        return true;
      }
      return value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
  }
}
