package com.apms.config;

import com.apms.common.enums.SystemRole;
import com.apms.domain.user.User;
import com.apms.domain.user.repository.sql.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        log.info("Running Development DataSeeder...");

        createUserIfNotFound("owner@apms.com", "Business", "Owner", SystemRole.BUSINESS_OWNER);
        createUserIfNotFound("manager@apms.com", "Business", "Manager", SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        createUserIfNotFound("staff@apms.com", "Research", "Staff", SystemRole.RESEARCH_STAFF);

        log.info("Development DataSeeder completed.");
    }

    private void createUserIfNotFound(String email, String firstName, String lastName, SystemRole role) {
        if (!userRepository.existsByEmail(email)) {
            User user = User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode("123456"))
                    .firstName(firstName)
                    .lastName(lastName)
                    .isActive(true)
                    .roles(Set.of(role))
                    .build();
            userRepository.save(user);
            log.info("Created demo user: {} with role: {}", email, role);
        } else {
            log.info("Demo user already exists: {}", email);
        }
    }
}
