package com.apms.common.util;

import java.util.List;

public class CsvExportUtil {

    public static String escapeField(String field) {
        if (field == null) {
            return "";
        }
        String sanitized = field;
        if (sanitized.startsWith("=") || sanitized.startsWith("+") || 
            sanitized.startsWith("-") || sanitized.startsWith("@") || 
            sanitized.startsWith("\t") || sanitized.startsWith("\r")) {
            sanitized = "'" + sanitized;
        }
        // If the field contains a comma, quote, or newline, we must wrap it in quotes and escape internal quotes
        if (sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n") || sanitized.contains("\r") || sanitized.contains(";")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }

    public static String escapeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        // Join with semicolon
        String joined = String.join(";", list);
        return escapeField(joined);
    }
}
