package com.apms.domain.assistant.service;

import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.regex.Pattern;

public final class ConversationalIntentDetector {

    private ConversationalIntentDetector() {}

    public enum ConversationalIntent {
        GREETING,
        THANK_YOU,
        CAPABILITIES,
        NONE
    }

    private static final Set<String> EXACT_GREETINGS = Set.of(
            "hi", "hello", "hey", "hi there", "hello there", "hey there",
            "good morning", "good afternoon", "good evening", "greetings",
            "xin chao", "xin chào", "chao ban", "chào bạn", "chao", "chào"
    );

    private static final Set<String> EXACT_THANK_YOU = Set.of(
            "thanks", "thank you", "thanks a lot", "thank you so much", "thank you very much",
            "many thanks", "thx",
            "cam on", "cảm ơn", "cam on ban", "cảm ơn bạn", "cam on nhieu", "cảm ơn nhiều",
            "ok", "okay", "k", "alright", "got it", "understood", "cool", "perfect",
            "great thanks", "great thank you"
    );

    private static final Set<String> EXACT_CAPABILITY = Set.of(
            "what can you do", "what can you do for me",
            "how can you help me", "how can you help", "how do you help",
            "help", "help me", "can you help me",
            "what are your capabilities", "what can i ask", "what can i ask you",
            "what do you do",
            "ban co the lam gi", "bạn có thể làm gì",
            "ban giup duoc gi", "bạn giúp được gì",
            "giup toi", "giúp tôi"
    );

    private static final Pattern BUSINESS_KEYWORDS = Pattern.compile(
            "\\b(task|tasks|work|action|actions|todo|assigned|project|projects|deadline|deadlines|due|" +
            "priority|priorities|urgent|closest|submit|submits|submitted|submission|submissions|" +
            "review|reviews|reviewer|candidate|candidates|revision|revisions|returned|reject|rejected|redo|" +
            "status|progress|overdue|workload|company|companies|profile|profiles|partner|partners|" +
            "competitor|competitors|customer|customers|supplier|suppliers|ecosystem|relationship|relationships|" +
            "news|report|reports|confidential|internal|risk|risks|opportunity|opportunities|swot|closeness|" +
            "insights|signal|signals)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final String[] VIETNAMESE_BUSINESS_KEYWORDS = {
            "dự án", "du an", "công việc", "cong viec", "nhiệm vụ", "nhiem vu",
            "hạn", "han chot", "nộp", "nop", "đối tác", "doi tac", "đối thủ", "doi thu",
            "công ty", "cong ty", "quan hệ", "quan he"
    };

    public static ConversationalIntent detect(String question) {
        if (!StringUtils.hasText(question)) {
            return ConversationalIntent.NONE;
        }

        String cleaned = cleanText(question);
        if (cleaned.isEmpty()) {
            return ConversationalIntent.NONE;
        }

        // If the question explicitly contains business terms, route to business logic
        if (hasBusinessKeywords(cleaned)) {
            return ConversationalIntent.NONE;
        }

        // 1. Check EXACT match
        if (EXACT_GREETINGS.contains(cleaned)) {
            return ConversationalIntent.GREETING;
        }
        if (EXACT_THANK_YOU.contains(cleaned)) {
            return ConversationalIntent.THANK_YOU;
        }
        if (EXACT_CAPABILITY.contains(cleaned)) {
            return ConversationalIntent.CAPABILITIES;
        }

        // 2. Check capability phrases (e.g., "what can you do for me today", "how can you help me now")
        if (cleaned.startsWith("what can you do") || cleaned.startsWith("how can you help")
                || cleaned.startsWith("what are your capabilities") || cleaned.startsWith("bạn có thể làm gì")
                || cleaned.startsWith("ban co the lam gi") || cleaned.startsWith("bạn giúp được gì")
                || cleaned.startsWith("ban giup duoc gi")) {
            return ConversationalIntent.CAPABILITIES;
        }

        // 3. Check greeting prefixes (e.g., "hi bot", "hello assistant", "good morning team")
        if (cleaned.startsWith("hi ") || cleaned.startsWith("hello ") || cleaned.startsWith("hey ")
                || cleaned.startsWith("xin chào ") || cleaned.startsWith("xin chao ")
                || cleaned.startsWith("chào bạn ") || cleaned.startsWith("chao ban ")) {
            return ConversationalIntent.GREETING;
        }

        // 4. Check thank-you prefixes (e.g., "thanks assistant", "thank you so much")
        if (cleaned.startsWith("thanks ") || cleaned.startsWith("thank you ")
                || cleaned.startsWith("cảm ơn ") || cleaned.startsWith("cam on ")) {
            return ConversationalIntent.THANK_YOU;
        }

        return ConversationalIntent.NONE;
    }

    private static boolean hasBusinessKeywords(String text) {
        if (BUSINESS_KEYWORDS.matcher(text).find()) {
            return true;
        }
        for (String kw : VIETNAMESE_BUSINESS_KEYWORDS) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String cleanText(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String noPunct = lower.replaceAll("[\\p{Punct}]+", " ");
        return noPunct.replaceAll("\\s+", " ").trim();
    }
}
