package com.apms.domain.profile.validation;

import com.apms.common.exception.BusinessValidationException;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared validation for Company Profile fields across Manager profile updates
 * and Staff monitoring proposals.
 */
public final class CompanyProfileFieldValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_ALLOWED_PATTERN = Pattern.compile("^\\+?[0-9\\s\\-()]+$");

    private CompanyProfileFieldValidator() {}

    public static void validateEmail(String email) {
        if (email == null) return;
        String trimmed = email.trim();
        if (trimmed.isEmpty()) return;

        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessValidationException("Enter a valid email address.");
        }
    }

    public static void validateEmails(Collection<?> emails) {
        if (emails == null) return;
        for (Object item : emails) {
            if (item != null) {
                validateEmail(item.toString());
            }
        }
    }

    public static void validatePhone(String phone) {
        if (phone == null) return;
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) return;

        if (!PHONE_ALLOWED_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessValidationException("Enter a valid phone number.");
        }

        String digitsOnly = trimmed.replaceAll("\\D", "");
        if (digitsOnly.length() < 8 || digitsOnly.length() > 15) {
            throw new BusinessValidationException("Enter a valid phone number.");
        }
    }

    public static void validatePhones(Collection<?> phones) {
        if (phones == null) return;
        for (Object item : phones) {
            if (item != null) {
                validatePhone(item.toString());
            }
        }
    }

    public static void validateWebsite(String website) {
        if (website == null) return;
        String trimmed = website.trim();
        if (trimmed.isEmpty()) return;

        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new BusinessValidationException("Enter a valid website URL, e.g. https://example.com");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank() || !host.contains(".") || host.startsWith(".") || host.endsWith(".")) {
                throw new BusinessValidationException("Enter a valid website URL, e.g. https://example.com");
            }
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessValidationException("Enter a valid website URL, e.g. https://example.com");
        }
    }

    public static void validateContact(String website, Collection<String> emails, Collection<String> phones) {
        validateWebsite(website);
        validateEmails(emails);
        validatePhones(phones);
    }

    public static void validateProposedContact(Map<String, Object> contactMap) {
        if (contactMap == null) return;

        Object websiteObj = contactMap.get("website");
        if (websiteObj instanceof String) {
            validateWebsite((String) websiteObj);
        }

        Object emailsObj = contactMap.get("emails");
        if (emailsObj instanceof Collection<?>) {
            validateEmails((Collection<?>) emailsObj);
        } else if (emailsObj instanceof String) {
            validateEmail((String) emailsObj);
        }

        Object emailObj = contactMap.get("email");
        if (emailObj instanceof String) {
            validateEmail((String) emailObj);
        }

        Object phonesObj = contactMap.get("phones");
        if (phonesObj instanceof Collection<?>) {
            validatePhones((Collection<?>) phonesObj);
        } else if (phonesObj instanceof String) {
            validatePhone((String) phonesObj);
        }

        Object phoneObj = contactMap.get("phone");
        if (phoneObj instanceof String) {
            validatePhone((String) phoneObj);
        }
    }
}
