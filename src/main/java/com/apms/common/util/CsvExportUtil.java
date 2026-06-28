package com.apms.common.util;

import java.util.List;

public class CsvExportUtil {

    public static String escapeField(String field) {
        if (field == null) {
            return "";
        }
        // If the field contains a comma, quote, or newline, we must wrap it in quotes and escape internal quotes
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains(";")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
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
