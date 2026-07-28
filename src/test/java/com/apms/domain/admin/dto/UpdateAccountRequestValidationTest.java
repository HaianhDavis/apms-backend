package com.apms.domain.admin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateAccountRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validRequest_noViolations() {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("John Doe");
        req.setEmail("john@example.com");
        req.setUsername("johndoe");
        req.setPassword("securepass123");

        Set<ConstraintViolation<UpdateAccountRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }

    @Test
    void blankName_violation() {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("");
        req.setEmail("john@example.com");

        Set<ConstraintViolation<UpdateAccountRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("name")));
    }

    @Test
    void invalidEmail_violation() {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("John");
        req.setEmail("not-an-email");

        Set<ConstraintViolation<UpdateAccountRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("email")));
    }

    @Test
    void shortPassword_violation() {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("John");
        req.setEmail("john@example.com");
        req.setPassword("1234567");

        Set<ConstraintViolation<UpdateAccountRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("password")));
    }

    @Test
    void shortUsername_violation() {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("John");
        req.setUsername("");

        Set<ConstraintViolation<UpdateAccountRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("username")));
    }

    @Test
    void nullOptionalFields_noViolation() {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("A");
        req.setEmail("a@b.com");
        req.setUsername("ab");
        req.setPassword("12345678");
        req.setRole(null);
        req.setActive(null);

        Set<ConstraintViolation<UpdateAccountRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }
}
