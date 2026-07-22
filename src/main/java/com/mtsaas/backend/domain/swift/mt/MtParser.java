package com.mtsaas.backend.domain.swift.mt;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MtParser {

    // Extract Block 4 (main message) {4: ... -}
    private static final Pattern BLOCK_4_PATTERN = Pattern.compile("\\{4:([\\s\\S]*?)-\\}");

    // Extract MT type from block 2: {2:O103...}, {2:O202...}
    private static final Pattern MT_TYPE_PATTERN = Pattern.compile("\\{2:[OI](\\d{3})", Pattern.CASE_INSENSITIVE);

    // Extract Block 1 (Sender BIC): {1:F01BANKDEFFAXXX...}
    private static final Pattern BLOCK_1_PATTERN = Pattern.compile("\\{1:[A-Z]{1}\\d{2}([A-Z0-9]{12})(?:\\d{10})?\\}", Pattern.CASE_INSENSITIVE);

    // Extract Block 2 (Receiver BIC): {2:O1030000000000BANKBEBBAXXX...}
    private static final Pattern BLOCK_2_RECV_PATTERN = Pattern.compile("\\{2:[OI]\\d{3}\\d{10}([A-Z0-9]{12})", Pattern.CASE_INSENSITIVE);
    // Fallback for short/non-standard Block 2 forms like: {2:1103PNBPUS3NXNYCN}
    private static final Pattern BLOCK_2_CONTENT_PATTERN = Pattern.compile("\\{2:([^}]*)\\}");
    private static final Pattern BIC_11_PATTERN = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}[A-Z0-9]{3}", Pattern.CASE_INSENSITIVE);

    // Extract Block 3 (User Header) fields: {3:{121:uuid}...}
    // Updated regex to handle nested braces correctly
    private static final Pattern BLOCK_3_PATTERN = Pattern.compile("\\{3:((?:\\{[^}]*\\})*)\\}");
    private static final Pattern BLOCK_3_TAG_PATTERN = Pattern.compile("(\\d{3}):([^\\}]+)");

    public MtMessage parse(String content) {
        MtMessage message = new MtMessage();
        Map<String, String> tags = new HashMap<>();

        // Normalize line endings and trim BOM if present
        if (content != null) {
            content = content.replace("\uFEFF", "");
            content = content.replace("\r\n", "\n").replace("\r", "\n");
        } else {
            message.setType("Unknown");
            message.setTags(tags);
            return message;
        }

        validateBlock1Syntax(content);

        // --- Extract MT type from block 2 ---
        Matcher mtTypeMatcher = MT_TYPE_PATTERN.matcher(content);
        if (mtTypeMatcher.find()) {
            String baseType = mtTypeMatcher.group(1); // e.g., "103", "202"

            // Check for COV indicator in Block 3 for MT202COV
            if ("202".equals(baseType) && content.toUpperCase().contains("{119:COV}")) {
                message.setType("202COV");
            } else {
                message.setType(baseType);
            }
        } else if (content.contains("103")) {
            message.setType("103"); // fallback
        } else {
            message.setType("Unknown");
        }

        // --- Extract Sender BIC from block 1 ---
        Matcher senderMatcher = BLOCK_1_PATTERN.matcher(content);
        if (senderMatcher.find()) {
            String fullBic = senderMatcher.group(1).toUpperCase();
            // SWIFT BICs in Block 1 are 12 chars: BANK(4)+CO(2)+L(2)+FILLER(1)+BRN(3)
            // Strip the 9th character (index 8) if it's 12 chars long.
            if (fullBic.length() == 12) {
                fullBic = fullBic.substring(0, 8) + fullBic.substring(9);
            }
            message.setSender(fullBic);
        }

        // --- Extract Receiver BIC from block 2 ---
        Matcher receiverMatcher = BLOCK_2_RECV_PATTERN.matcher(content);
        if (receiverMatcher.find()) {
            String fullBic = receiverMatcher.group(1).toUpperCase();
            if (fullBic.length() == 12) {
                fullBic = fullBic.substring(0, 8) + fullBic.substring(9);
            }
            message.setReceiver(fullBic);
        } else {
            extractReceiverFromFlexibleBlock2(content).ifPresent(message::setReceiver);
        }

        // --- Extract Block 3 (User Header) ---
        Matcher block3Matcher = BLOCK_3_PATTERN.matcher(content);
        if (block3Matcher.find()) {
            String block3 = block3Matcher.group(1);
            Matcher block3TagMatcher = BLOCK_3_TAG_PATTERN.matcher(block3);
            while (block3TagMatcher.find()) {
                String tagNum = block3TagMatcher.group(1);
                String value = block3TagMatcher.group(2).trim();
                tags.put(tagNum, value); // e.g., "121" -> "uuid-value"
            }
        }

        // --- Extract Block 4 (Message Tags) ---
        Matcher block4Matcher = BLOCK_4_PATTERN.matcher(content);
        String block4 = null;
        if (block4Matcher.find()) {
            block4 = block4Matcher.group(1);
        } else {
            // Fallback: allow parsing when block 4 wrapper is missing
            block4 = content;
        }

        // Check if block4 is null and return early if so
        if (block4 == null) {
            message.setTags(tags);
            return message;
        }

        Pattern tokenPattern = Pattern.compile("(?<!\\S):([0-9]{2}[A-Z]?):", Pattern.CASE_INSENSITIVE);
        Matcher tokenMatcher = tokenPattern.matcher(block4);

        int lastMatchEnd = -1;
        String lastTag = null;

        while (tokenMatcher.find()) {
            if (lastTag != null) {
                String value = block4.substring(lastMatchEnd, tokenMatcher.start()).trim();
                tags.put(lastTag, cleanTagValue(lastTag, value));
            }
            lastTag = tokenMatcher.group(1).toUpperCase();
            lastMatchEnd = tokenMatcher.end();
        }

        // Add the last tag
        if (lastTag != null) {
            String value = block4.substring(lastMatchEnd).trim();
            tags.put(lastTag, cleanTagValue(lastTag, value));
        }

        message.setTags(tags);

        // --- Optional: validate MT103 or MT202 or MT940 ---
        if ("103".equals(message.getType())) {
            validateMt103(tags);
        } else if ("202".equals(message.getType())) {
            validateMt202(tags);
        } else if ("940".equals(message.getType())) {
            validateMt940(tags);
        }

        return message;
    }

    private void validateBlock1Syntax(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Syntax error in SWIFT Block 1: message content is empty.");
        }

        int block1Start = content.indexOf("{1:");
        if (block1Start < 0) {
            throw new IllegalArgumentException("Syntax error in SWIFT Block 1: missing {1: block.");
        }

        int block1End = content.indexOf('}', block1Start + 3);
        if (block1End < 0) {
            throw new IllegalArgumentException("Syntax error in SWIFT Block 1: missing closing }.");
        }

        String block1 = content.substring(block1Start, block1End + 1);
        if (!BLOCK_1_PATTERN.matcher(block1).matches()) {
            throw new IllegalArgumentException("Syntax error in SWIFT Block 1: malformed header at line 1, column " + (block1Start + 1) + ".");
        }
    }

    private java.util.Optional<String> extractReceiverFromFlexibleBlock2(String content) {
        Matcher block2Matcher = BLOCK_2_CONTENT_PATTERN.matcher(content);
        if (!block2Matcher.find()) {
            return java.util.Optional.empty();
        }

        String block2Content = block2Matcher.group(1).toUpperCase();
        Matcher bicMatcher = BIC_11_PATTERN.matcher(block2Content);
        if (!bicMatcher.find()) {
            return java.util.Optional.empty();
        }

        String bic = bicMatcher.group();
        if (bic.length() == 11) {
            return java.util.Optional.of(bic);
        }
        return java.util.Optional.empty();
    }

    private String cleanTagValue(String tag, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue;

        int structuralBoundary = findFirstBoundary(value, "{5:", "-}", "<?xml", "<RequestPayload", "{1:", "{2:", "{3:", "{4:");
        if (structuralBoundary >= 0) {
            value = value.substring(0, structuralBoundary);
        }

        value = value.trim();
        if ("71A".equals(tag)) {
            value = normalizeChargeTag(value);
        }
        return value;
    }

    private int findFirstBoundary(String value, String... boundaries) {
        int boundaryIndex = -1;
        for (String boundary : boundaries) {
            int idx = value.indexOf(boundary);
            if (idx >= 0 && (boundaryIndex < 0 || idx < boundaryIndex)) {
                boundaryIndex = idx;
            }
        }
        return boundaryIndex;
    }

    private String normalizeChargeTag(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.startsWith("OUR")) {
            return "OUR";
        }
        if (normalized.startsWith("BEN")) {
            return "BEN";
        }
        if (normalized.startsWith("SHA") || normalized.startsWith("SHAR")) {
            return "SHA";
        }
        return normalized;
    }

    private void validateMt202(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        if (!tags.containsKey("20"))
            System.err.println("MT202 Advisory: Missing mandatory tag :20: (Transaction Reference Number)");
        if (!tags.containsKey("21"))
            System.err.println("MT202 Advisory: Missing mandatory tag :21: (Related Reference)");
        if (!tags.containsKey("32A"))
            System.err.println("MT202 Advisory: Missing mandatory tag :32A: (Value Date, Currency Code, Amount)");
        if (!tags.containsKey("58A") && !tags.containsKey("58D"))
            System.err.println("MT202 Advisory: Missing mandatory tag :58a: (Beneficiary Institution)");
    }

    private void validateMt103(Map<String, String> tags) {
        if (tags == null) {
            return;
        }

        if (!tags.containsKey("20"))
            System.err.println("MT103 Advisory: Missing mandatory tag :20: (Transaction Reference Number)");
        if (!tags.containsKey("32A"))
            System.err.println("MT103 Advisory: Missing mandatory tag :32A: (Value Date, Currency Code, Amount)");
        if (!tags.containsKey("71A"))
            System.err.println("MT103 Advisory: Missing mandatory tag :71A: (Details of Charges)");

        boolean has50 = tags.keySet().stream().anyMatch(k -> k.startsWith("50"));
        if (!has50)
            System.err.println("MT103 Advisory: Missing mandatory tag :50a: (Ordering Customer)");

        boolean has59 = tags.keySet().stream().anyMatch(k -> k.startsWith("59"));
        if (!has59)
            System.err.println("MT103 Advisory: Missing mandatory tag :59a: (Beneficiary Customer)");
    }

    private void validateMt940(Map<String, String> tags) {
        if (tags == null) {
            return;
        }

        if (!tags.containsKey("20"))
            System.err.println("MT940 Advisory: Missing mandatory tag :20: (Transaction Reference Number)");
        if (!tags.containsKey("25"))
            System.err.println("MT940 Advisory: Missing mandatory tag :25: (Account Identification)");
        if (!tags.containsKey("28C"))
            System.err.println("MT940 Advisory: Missing mandatory tag :28C: (Statement Number/Sequence)");

        boolean has60 = tags.containsKey("60F") || tags.containsKey("60M");
        if (!has60)
            System.err.println("MT940 Advisory: Missing mandatory tag :60F: or :60M: (Opening Balance)");

        boolean has62 = tags.containsKey("62F") || tags.containsKey("62M");
        if (!has62)
            System.err.println("MT940 Advisory: Missing mandatory tag :62F: or :62M: (Closing Balance)");
    }
}
