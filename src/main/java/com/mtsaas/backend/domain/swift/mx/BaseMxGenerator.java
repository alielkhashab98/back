package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import com.mtsaas.backend.infrastructure.xml.XmlValidator;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

public abstract class BaseMxGenerator implements MxGenerator {
    private static final Set<String> ISO_COUNTRY_CODES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));

    @Setter(onMethod_ = @Autowired)
    private XmlValidator xmlValidator;

    @Override
    public String generate(MtMessage mtMessage) {
        validateInput(mtMessage);
        return generateAndValidateXml(mtMessage);
    }

    protected final String generateAndValidateXml(MtMessage mtMessage) {
        String xml = generateXml(mtMessage);
        if (xmlValidator != null) {
            String validationContent = xml;
            // Extract the Document part if present to validate against standard XSD
            int startIndex = xml.indexOf("<Document");
            if (startIndex >= 0) {
                int endIndex = xml.indexOf("</Document>") + "</Document>".length();
                if (endIndex > startIndex) {
                    validationContent = xml.substring(startIndex, endIndex);
                }
            }
            xmlValidator.validate(validationContent, getXsdPath());
        }
        return xml;
    }

    /**
     * Validates the input MT message structure and fields.
     */
    protected abstract void validateInput(MtMessage mtMessage);

    /**
     * Generates the raw XML string.
     */
    protected abstract String generateXml(MtMessage mtMessage);

    /**
     * Returns the classpath path to the XSD file for validation.
     */
    protected abstract String getXsdPath();

    /**
     * Sanitizes a BIC to ensure it matches the ISO 20022 pattern: 8 or 11
     * characters.
     */
    protected String sanitizeBic(String bic) {
        if (bic == null || bic.isBlank() || "UNDEFINED".equalsIgnoreCase(bic) || "NOTPROVIDED".equalsIgnoreCase(bic)) {
            return "UNKNUSXXXXX"; // 11 chars, valid pattern (Bank: UNKN, Country: US, Loc: XX, Branch:
                                  // Placeholder)
        }

        // Remove non-alphanumeric
        String clean = bic.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

        if (clean.length() == 8 || clean.length() == 11) {
            return clean;
        }

        if (clean.length() > 11) {
            return clean.substring(0, 11);
        }

        if (clean.length() > 8 && clean.length() < 11) {
            // Instead of truncating, let's pad to 11 to satisfy the facet
            StringBuilder sb = new StringBuilder(clean);
            while (sb.length() < 11) {
                sb.append("X");
            }
            return sb.toString();
        }

        // Too short? Pad with X to 8
        StringBuilder sb = new StringBuilder(clean);
        while (sb.length() < 8) {
            sb.append("X");
        }
        return sb.toString();
    }

    /**
     * Extracts amount from SWIFT field 32A/33B etc.
     */
    protected String extractAmount(String tagValue) {
        if (tagValue == null || tagValue.length() <= 9)
            return "0.00";
        // Tag 32A: 6(Date)+3(CCY)+Amount
        return normalizeAmount(tagValue.substring(9));
    }

    /**
     * Normalizes SWIFT amounts (replaces comma with dot, ensures decimals).
     */
    protected String normalizeAmount(String raw) {
        if (raw == null || raw.isBlank())
            return "0.00";
        String s = raw.trim().replace(" ", "").replace(",", ".");
        if (!s.contains(".")) {
            s += ".00";
        } else if (s.endsWith(".")) {
            s += "00";
        } else {
            // Ensure at least 2 decimal places if needed?
            // SWIFT often has variable decimals. ISO 20022 likes them.
            // If it ends with .5, maybe .50?
            int dotIdx = s.indexOf('.');
            if (s.length() - dotIdx == 2) {
                s += "0";
            }
        }
        return s;
    }

    protected static final int MAX_NAME_LEN = 140;
    protected static final int MAX_ADR_LINE_LEN = 70;
    protected static final int MAX_ADR_LINES = 7;
    protected static final int MAX_ID_LEN = 35;
    protected static final int MAX_IBAN_LEN = 34;

    /**
     * Escapes XML special characters.
     */
    protected String escapeXml(String input) {
        if (input == null)
            return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    protected boolean hasAnyTag(Map<String, String> tags, String baseKey) {
        if (baseKey == null)
            return false;
        if (tags.containsKey(baseKey))
            return true;
        String[] suffixes = { "A", "B", "D", "F", "K", "C" };
        for (String s : suffixes)
            if (tags.containsKey(baseKey + s))
                return true;
        return false;
    }

    protected String getSettlementMethod(Map<String, String> tags) {
        // CBPR+ Rule:
        // Bilateral correspondent -> INDA
        // Clearing system used -> CLRG
        // RTGS -> RTGS

        // If no clearing system identified (53, 54, 56 chain) -> INDA
        if (hasAnyTag(tags, "53") || hasAnyTag(tags, "54") || hasAnyTag(tags, "56")) {
            // Check for specific clearing codes if possible, default to CLRG if
            // correspondent exists
            return "CLRG";
        }
        return "INDA";
    }

    protected boolean appendAgent(StringBuilder xml, String role, String acctRole, Map<String, String> tags,
            String tagBase) {
        String contentA = tags.get(tagBase + "A");
        String contentD = tags.get(tagBase + "D");
        String contentF = tags.get(tagBase + "F");
        String contentB = tags.get(tagBase + "B");
        String content = tags.get(tagBase);

        String raw = (contentA != null && !contentA.isBlank()) ? contentA
                : (contentD != null && !contentD.isBlank()) ? contentD
                        : (contentF != null && !contentF.isBlank()) ? contentF
                                : (contentB != null && !contentB.isBlank()) ? contentB : content;

        if (raw == null || raw.isBlank())
            return false;

        ParsedParty parsed = parsePartyContent(raw, true);

        xml.append("        <").append(role).append(">\n");
        xml.append("          <FinInstnId>\n");

        if (parsed.getBic() != null) {
            // Priority: Regex-extracted BIC (for A, D, F variants)
            String bic = sanitizeBic(parsed.getBic());
            xml.append("            <BICFI>").append(escapeXml(bic)).append("</BICFI>\n");
        } else {
            String name = parsed.getName();
            if (name == null || name.isBlank()) {
                name = "UNKNOWN BANK";
            }
            xml.append("            <Nm>").append(escapeXml(truncate(extractName(name), MAX_NAME_LEN)))
                    .append("</Nm>\n");

            if (!parsed.getAddressLines().isEmpty()
                    || (parsed.getCountry() != null && !parsed.getCountry().isBlank())) {
                xml.append("            <PstlAdr>\n");
                if (parsed.getCountry() != null && !parsed.getCountry().isBlank()) {
                    xml.append("              <Ctry>").append(escapeXml(parsed.getCountry())).append("</Ctry>\n");
                }
                for (String adr : parsed.getAddressLines()) {
                    xml.append("              <AdrLine>").append(escapeXml(truncate(adr, MAX_ADR_LINE_LEN)))
                            .append("</AdrLine>\n");
                }
                xml.append("            </PstlAdr>\n");
            }
        }

        xml.append("          </FinInstnId>\n");
        xml.append("        </").append(role).append(">\n");

        // Map Account if present and acctRole provided
        if (acctRole != null && parsed.getAccount() != null && !parsed.getAccount().isBlank()) {
            String cleanAccount = parsed.getAccount().replaceAll("\\s+", "");
            xml.append("        <").append(acctRole).append(">\n");
            xml.append("          <Id>\n");
            xml.append("            <Othr>\n");
            xml.append("              <Id>").append(escapeXml(cleanAccount)).append("</Id>\n");
            xml.append("            </Othr>\n");
            xml.append("          </Id>\n");
            xml.append("        </").append(acctRole).append(">\n");
        }

        return true;
    }

    protected void appendParty(StringBuilder xml, String role, String acctRole,
            Map<String, String> tags, String rootTag, String... variants) {

        String content = null;
        String tagUsed = null;

        for (String key : variants) {
            if (tags.containsKey(key)) {
                content = tags.get(key);
                tagUsed = key;
                break;
            }
            if (tags.containsKey(key + "A")) {
                content = tags.get(key + "A");
                tagUsed = key + "A";
                break;
            }
            if (tags.containsKey(key + "D")) {
                content = tags.get(key + "D");
                tagUsed = key + "D";
                break;
            }
            if (tags.containsKey(key + "F")) {
                content = tags.get(key + "F");
                tagUsed = key + "F";
                break;
            }
        }
        if (content == null && tags.containsKey(rootTag)) {
            content = tags.get(rootTag);
            tagUsed = rootTag;
        }
        if (content == null)
            return;

        if (tagUsed != null && tagUsed.endsWith("A")) {
            String bic = content.trim().replaceAll("\\s+", "");
            if (!bic.isEmpty()) {
                xml.append("        <").append(role).append(">\n");
                xml.append("          <Id>\n");
                xml.append("            <OrgId>\n");
                xml.append("              <AnyBIC>").append(escapeXml(sanitizeBic(bic))).append("</AnyBIC>\n");
                xml.append("            </OrgId>\n");
                xml.append("          </Id>\n");
                xml.append("        </").append(role).append(">\n");
                return;
            }
        }

        ParsedParty parsed = parsePartyContent(content, false);
        if (parsed == null)
            return;

        String name = parsed.getName();
        // Fallback: If name is missing, try use first address line
        if ((name == null || name.isBlank() || "NOTPROVIDED".equals(name)) && !parsed.getAddressLines().isEmpty()) {
            name = parsed.getAddressLines().get(0);
            parsed.getAddressLines().remove(0);
        }

        if (name == null || name.isBlank()) {
            name = "UNKNOWN PARTY";
        }

        xml.append("        <").append(role).append(">\n");
        xml.append("          <Nm>").append(escapeXml(truncate(extractName(name), MAX_NAME_LEN))).append("</Nm>\n");
        if (!parsed.getAddressLines().isEmpty() || (parsed.getCountry() != null && !parsed.getCountry().isBlank())) {
            xml.append("          <PstlAdr>\n");
            if (parsed.getCountry() != null && !parsed.getCountry().isBlank()) {
                xml.append("            <Ctry>").append(escapeXml(parsed.getCountry())).append("</Ctry>\n");
            }
            for (String ln : parsed.getAddressLines()) {
                xml.append("            <AdrLine>").append(escapeXml(truncate(ln, MAX_ADR_LINE_LEN)))
                        .append("</AdrLine>\n");
            }
            xml.append("          </PstlAdr>\n");
        }
        xml.append("        </").append(role).append(">\n");

        String account = parsed.getAccount();
        if (account != null && !account.isBlank()) {
            String cleanAccount = account.replaceAll("\\s+", ""); // Pure account for IBAN/ID

            xml.append("        <").append(acctRole).append(">\n");
            xml.append("          <Id>\n");
            if (isValidIBAN(cleanAccount)) {
                // NO TRUNCATION for IBAN
                xml.append("            <IBAN>").append(escapeXml(cleanAccount)).append("</IBAN>\n");
            } else {
                xml.append("            <Othr>\n");
                // NO TRUNCATION for ID
                xml.append("              <Id>").append(escapeXml(cleanAccount)).append("</Id>\n");
                xml.append("            </Othr>\n");
            }
            xml.append("          </Id>\n");
            xml.append("        </").append(acctRole).append(">\n");
        }
    }

    /**
     * Parses party/agent content from an MT tag value.
     * @param content  the raw tag value
     * @param scanForBic  true for agent tags (52, 53, 56, 57) where BICs are expected;
     *                    false for party tags (50K, 59) where names should not be mistaken for BICs
     */
    protected ParsedParty parsePartyContent(String content, boolean scanForBic) {
        if (content == null)
            return null;
        String[] rawLines = content.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String raw : rawLines) {
            if (raw == null)
                continue;
            String trimmed = raw.trim();
            if (!trimmed.isEmpty())
                lines.add(trimmed);
        }
        if (lines.isEmpty())
            return new ParsedParty(null, null, new ArrayList<>(), null, null);

        String account = null;
        String name = null;
        String country = null;
        String extractedBic = null;
        List<String> addressLines = new ArrayList<>();

        // Step 1: Scan all lines for a BIC — only for agent tags where BICs are expected.
        // For party tags (50K, 59), names like "VARAGANI" or "BORISGAEL" can falsely match
        // the BIC pattern and get removed, causing critical data loss.
        if (scanForBic) {
            // BIC Regex: [A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?
            java.util.regex.Pattern bicPattern = java.util.regex.Pattern
                    .compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

            for (int i = 0; i < lines.size(); i++) {
                String ln = lines.get(i);
                java.util.regex.Matcher matcher = bicPattern.matcher(ln);
                if (matcher.find()) {
                    extractedBic = matcher.group();
                    // If the line consists only of the BIC, remove the line to avoid duplication.
                    if (ln.trim().equals(extractedBic)) {
                        lines.remove(i);
                        i--;
                    } else {
                        // Otherwise, just remove the BIC from the line
                        lines.set(i, ln.replace(extractedBic, "").replaceAll("\\s+", " ").trim());
                    }
                    break; // Take the first found BIC
                }
            }
        }

        if (lines.isEmpty()) {
            return new ParsedParty(null, extractedBic, new ArrayList<>(), null, extractedBic);
        }

        // Step 2: Extract Account (if line starts with /)
int startLine = 0;
String firstLine = lines.get(0);
if (firstLine.startsWith("/")) {
    String remainder = firstLine.substring(1).trim();
    
    // Extract account: take leading alphanumeric characters (SWIFT accounts are typically numeric)
    // Important: Don't use first space as delimiter since account can be directly concatenated with name
    int accountEndIdx = 0;
    while (accountEndIdx < remainder.length() && 
           Character.isLetterOrDigit(remainder.charAt(accountEndIdx))) {
        accountEndIdx++;
    }
    
    if (accountEndIdx > 0) {
        account = remainder.substring(0, accountEndIdx).trim();
        String afterAccount = remainder.substring(accountEndIdx).trim();
        
        // If there's content after the account, it's the name (could be on same line)
        if (!afterAccount.isEmpty()) {
            name = afterAccount;
        }
    } else {
        // Fallback: if no alphanumeric found, treat entire remainder as account
        account = remainder;
    }
    startLine = 1;
}

        // Step 3: Extract Name (first non-account line, if name not already found)
        if (startLine < lines.size()) {
            if (name == null) {
                name = lines.get(startLine);
                startLine++;
            }
        }

        // Step 4: Extract Address lines and Country
        for (int i = startLine; i < lines.size(); i++) {
            addressLines.add(lines.get(i));
        }

        // Check for country in the last line using token-aware parsing.
        if (!addressLines.isEmpty()) {
            String lastLine = addressLines.get(addressLines.size() - 1);
            CountryLineParse parsedCountryLine = extractCountryFromAddressLine(lastLine);
            if (parsedCountryLine.countryCode() != null) {
                country = parsedCountryLine.countryCode();
                if (parsedCountryLine.remainingLine().isEmpty()) {
                    addressLines.remove(addressLines.size() - 1);
                } else {
                    addressLines.set(addressLines.size() - 1, parsedCountryLine.remainingLine());
                }
            }
        }

        return new ParsedParty(account, name, addressLines, country, extractedBic);
    }

    protected static final class ParsedParty {
        private final String account;
        private final String name;
        private final List<String> addressLines;
        private final String country;
        private final String bic;

        protected ParsedParty(String account, String name, List<String> addressLines, String country, String bic) {
            this.account = account == null ? null : account.trim();
            this.name = name == null ? null : name.trim();
            this.addressLines = addressLines != null ? addressLines : new ArrayList<>();
            this.country = country == null ? null : country.trim();
            this.bic = bic;
        }

        public String getAccount() {
            return account;
        }

        public String getName() {
            return name;
        }

        public List<String> getAddressLines() {
            return addressLines;
        }

        public String getCountry() {
            return country;
        }

        public String getBic() {
            return bic;
        }
    }

    protected String stripTokenEdges(String value, String token) {
        if (value == null)
            return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty())
            return trimmed;
        List<String> tokensToRemove = new ArrayList<>(Arrays.asList("SA", "AE", "BH", "KW", "OM", "QA"));
        if (token != null && !token.isBlank())
            tokensToRemove.add(token);
        List<String> parts = new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
        boolean changed;
        do {
            changed = false;
            if (!parts.isEmpty()) {
                String first = parts.get(0).replaceAll("[^A-Za-z]", "").toUpperCase();
                if (tokensToRemove.contains(first)) {
                    parts.remove(0);
                    changed = true;
                }
            }
        } while (changed);
        return String.join(" ", parts);
    }

    protected String truncate(String input, int max) {
        if (input == null)
            return "";
        String s = input.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    protected String[] splitAdrLines(String address, int maxLen, int maxLines) {
        if (address == null)
            return new String[0];
        String s = address.replaceAll("\\s+", " ").trim();
        if (s.isEmpty())
            return new String[0];
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String token : s.split(" ")) {
            if (token.isEmpty())
                continue;
            if (line.length() == 0) {
                line.append(token.length() <= maxLen ? token : token.substring(0, maxLen));
            } else if (line.length() + 1 + token.length() <= maxLen) {
                line.append(" ").append(token);
            } else {
                out.add(line.toString());
                if (out.size() >= maxLines)
                    break;
                line.setLength(0);
                line.append(token.length() <= maxLen ? token : token.substring(0, maxLen));
            }
        }
        if (line.length() > 0 && out.size() < maxLines)
            out.add(line.toString());
        return out.toArray(new String[0]);
    }

    protected boolean isValidIBAN(String acct) {
        return acct != null && acct.matches("^[A-Z]{2}\\d{2}[A-Z0-9]{10,30}$");
    }

    protected String cleanAccount(String account) {
        return account.replaceAll("\\s+", " ").replaceAll("\\bSA\\b", "").trim();
    }

    protected String extractName(String candidate) {
        if (candidate == null || candidate.isBlank())
            return "UNKNOWN";
        return candidate.replaceAll("[^\\p{L}\\p{N}\\.\\-\\s]", "").trim();
    }

    private CountryLineParse extractCountryFromAddressLine(String line) {
        if (line == null || line.isBlank()) {
            return new CountryLineParse(null, line == null ? "" : line);
        }

        String cleaned = line.trim().replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        if (tokens.length < 2) {
            return new CountryLineParse(null, cleaned);
        }

        String firstToken = tokens[0].toUpperCase(Locale.ROOT);
        if (isValidCountryCode(firstToken)) {
            String remainder = cleaned.substring(tokens[0].length()).trim();
            return new CountryLineParse(firstToken, remainder);
        }

        String lastToken = tokens[tokens.length - 1].toUpperCase(Locale.ROOT);
        if (isValidCountryCode(lastToken)) {
            String remainder = cleaned.substring(0, cleaned.length() - tokens[tokens.length - 1].length()).trim();
            return new CountryLineParse(lastToken, remainder);
        }

        return new CountryLineParse(null, cleaned);
    }

    private boolean isValidCountryCode(String code) {
        return code != null && code.length() == 2 && ISO_COUNTRY_CODES.contains(code);
    }

    private record CountryLineParse(String countryCode, String remainingLine) {
    }
}
