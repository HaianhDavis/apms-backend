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

        createUserIfNotFound("admin@apms.com", "System", "Admin", SystemRole.SYSTEM_ADMIN);
        createUserIfNotFound("owner@apms.com", "Business", "Owner", SystemRole.BUSINESS_OWNER);
        createUserIfNotFound("manager1@apms.com", "Business", "Manager 1", SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        createUserIfNotFound("manager2@apms.com", "Business", "Manager 2", SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        createUserIfNotFound("manager3@apms.com", "Business", "Manager 3", SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        createUserIfNotFound("staff1@apms.com", "Research", "Staff 1", SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        createUserIfNotFound("staff2@apms.com", "Research", "Staff 2", SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        createUserIfNotFound("staff3@apms.com", "Research", "Staff 3", SystemRole.BUSINESS_DEVELOPMENT_STAFF);

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

    @org.springframework.beans.factory.annotation.Autowired
    private com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository companyCandidateRepository;
    
    @jakarta.annotation.PostConstruct
    public void printCandidate() {
        companyCandidateRepository.findByTaskId(1L).forEach(c -> {
            if (!"1".equals(c.getProjectId())) {
                c.setProjectId("1");
                companyCandidateRepository.save(c);
                log.info("FIXED CANDIDATE {} projectId to 1", c.getId());
            }
        });
    }
}


