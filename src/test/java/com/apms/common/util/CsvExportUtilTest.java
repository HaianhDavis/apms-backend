package com.apms.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CsvExportUtilTest {

    @Test
    @DisplayName("Sanitize field starting with '=' by adding single quote prefix")
    void escapeField_startsWithEquals_shouldAddSingleQuote() {
        String input = "=SUM(1+1)";
        String result = CsvExportUtil.escapeField(input);
        assertEquals("'=SUM(1+1)", result);
    }

    @Test
    @DisplayName("Sanitize field starting with '+' by adding single quote prefix")
    void escapeField_startsWithPlus_shouldAddSingleQuote() {
        String input = "+100";
        String result = CsvExportUtil.escapeField(input);
        assertEquals("'+100", result);
    }

    @Test
    @DisplayName("Sanitize field starting with '-' by adding single quote prefix")
    void escapeField_startsWithMinus_shouldAddSingleQuote() {
        String input = "-cmd|' /C calc'!A0";
        String result = CsvExportUtil.escapeField(input);
        assertTrue(result.startsWith("'-cmd|'"));
    }

    @Test
    @DisplayName("Sanitize field starting with '@' by adding single quote prefix")
    void escapeField_startsWithAt_shouldAddSingleQuote() {
        String input = "@SUM(A1:A10)";
        String result = CsvExportUtil.escapeField(input);
        assertEquals("'@SUM(A1:A10)", result);
    }

    @Test
    @DisplayName("Field with comma and formula prefix should be quoted and sanitized with single quote")
    void escapeField_withCommaAndFormula_shouldBeQuotedAndSanitized() {
        String input = "=cmd|' /C calc', malicious";
        String result = CsvExportUtil.escapeField(input);
        assertEquals("\"'=cmd|' /C calc', malicious\"", result);
    }

    @Test
    @DisplayName("Normal safe text field remains unchanged")
    void escapeField_normalText_shouldRemainUnchanged() {
        String input = "admin@apms.com";
        String result = CsvExportUtil.escapeField(input);
        assertEquals("admin@apms.com", result);
    }
}
