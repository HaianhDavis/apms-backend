package com.apms.config;

import com.apms.common.enums.SystemRole;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

import org.springframework.core.annotation.Order;

@Slf4j
@Component
@Profile("dev")
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final UserProfileRepository userProfileRepository;
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
        if (!accountRepository.existsByEmail(email)) {
            Account account = Account.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode("123456"))
                    .isActive(true)
                    .roles(Set.of(role))
                    .build();
            account = accountRepository.save(account);

            UserProfile profile = UserProfile.builder()
                    .account(account)
                    .firstName(firstName)
                    .lastName(lastName)
                    .build();
            userProfileRepository.save(profile);

            log.info("Created demo account & profile: {} with role: {}", email, role);
        } else {
            log.info("Demo account already exists: {}", email);
        }
    }
}
