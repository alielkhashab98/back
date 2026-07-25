package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import com.mtsaas.backend.domain.swift.mt.MtParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service class that translates legacy SWIFT MT101 payment initiation instructions
 * into ISO 20022 pain.001.001.09 XML payloads.
 */
@Service
public class MT101ToPain001Converter extends BaseMxGenerator {

    @Autowired
    private MtParser mtParser;

    /**
     * Converts a raw MT101 string into a pain.001.001.09 XML payload.
     * Validates that the MT101 contains only a single instruction before parsing.
     *
     * @param rawMt101 the raw MT101 message string
     * @return the generated ISO 20022 XML
     */
    public String convert(String rawMt101) {
        validateInstructionCount(rawMt101);
        MtMessage mtMessage = mtParser.parse(rawMt101);
        return generate(mtMessage);
    }

    /**
     * Converts a parsed MtMessage into a pain.001.001.09 XML payload.
     * Note: When using this method, multi-instruction validation may not be reliable
     * as duplicate tags (e.g. multiple :21:) are overwritten during map-based parsing.
     *
     * @param mtMessage the parsed MT101 message
     * @return the generated ISO 20022 XML
     */
    public String convert(MtMessage mtMessage) {
        return generate(mtMessage);
    }

    private void validateInstructionCount(String rawMt101) {
        if (rawMt101 == null) return;
        
        // Sequence B in MT101 contains the actual transfer instructions.
        // Each instruction starts with tag :21: (Transaction Reference).
        Matcher matcher = Pattern.compile("(?m)^:21:").matcher(rawMt101);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        
        // If there are multiple instructions, throw a compliance warning/error.
        if (count > 1) {
            throw new IllegalStateException("Compliance Error: Multi-instruction MT101s are not supported. " +
                    "They must be refactored or handled via contingency protocols. Found " + count + " instructions.");
        }
    }

    @Override
    public boolean supports(String mtType) {
        return "101".equalsIgnoreCase(mtType) || "MT101".equalsIgnoreCase(mtType);
    }

    @Override
    protected void validateInput(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        if (tags == null) {
            throw new IllegalArgumentException("MT101 tags cannot be null");
        }
        if (!tags.containsKey("20")) {
            throw new IllegalArgumentException("Missing mandatory tag :20: (Sender's Reference)");
        }
        boolean has50 = tags.keySet().stream().anyMatch(k -> k.equals("50") || k.startsWith("50"));
        if (!has50) {
            throw new IllegalArgumentException("Missing mandatory tag :50: (Instructing Party / Debtor)");
        }
        boolean has59 = tags.keySet().stream().anyMatch(k -> k.equals("59") || k.startsWith("59"));
        if (!has59) {
            throw new IllegalArgumentException("Missing mandatory tag :59: (Creditor)");
        }
        if (!tags.containsKey("32B")) {
            throw new IllegalArgumentException("Missing mandatory tag :32B: (Instructed Amount)");
        }
    }

    @Override
    protected String generateXml(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        StringBuilder xml = new StringBuilder();

        // Use tag 20 (Sender's Reference) for MsgId. If missing, generate a generic one.
        String msgId = tags.getOrDefault("20", "UNKNOWN_MSG_ID").trim();
        msgId = escapeXml(sanitizeId(msgId));

        // CR 3014 Guardrail: Programmatically enforce PmtInfId matches MsgId
        String pmtInfId = msgId;

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");
        
        // --- Group Header ---
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>2026-07-25T00:00:00Z</CreDtTm>\n"); // Default placeholder
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <InitgPty>\n");
        xml.append("        <Nm>INITIATING PARTY</Nm>\n");
        xml.append("      </InitgPty>\n");
        xml.append("    </GrpHdr>\n");

        // --- Payment Information ---
        xml.append("    <PmtInf>\n");
        xml.append("      <PmtInfId>").append(pmtInfId).append("</PmtInfId>\n");
        xml.append("      <PmtMtd>TRF</PmtMtd>\n");
        xml.append("      <ReqdExctnDt>\n");
        xml.append("        <Dt>2026-07-25</Dt>\n");
        xml.append("      </ReqdExctnDt>\n");

        // Debtor (Tag 50 series: 50, 50A, 50F, 50G, 50H, 50K)
        appendDebtor(xml, tags);

        // --- Credit Transfer Transaction Information ---
        xml.append("      <CdtTrfTxInf>\n");
        xml.append("        <PmtId>\n");
        // Often instruction ID is mapped to tag 21 (Transaction Reference for the instruction)
        String instrId = tags.getOrDefault("21", msgId).trim();
        xml.append("          <InstrId>").append(escapeXml(sanitizeId(instrId))).append("</InstrId>\n");
        xml.append("          <EndToEndId>").append(escapeXml(sanitizeId(instrId))).append("</EndToEndId>\n");
        xml.append("        </PmtId>\n");

        // Amount (Tag 32B)
        appendAmount(xml, tags);

        // Creditor (Tag 59 series)
        appendCreditor(xml, tags);

        xml.append("      </CdtTrfTxInf>\n");
        xml.append("    </PmtInf>\n");
        xml.append("  </CstmrCdtTrfInitn>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    private void appendDebtor(StringBuilder xml, Map<String, String> tags) {
        String content = null;
        String[] variants = {"50", "50A", "50F", "50G", "50H", "50K"};
        for (String v : variants) {
            if (tags.containsKey(v)) {
                content = tags.get(v);
                break;
            }
        }

        if (content == null) {
            // Fallback empty
            xml.append("      <Dbtr>\n        <Nm>UNKNOWN DEBTOR</Nm>\n      </Dbtr>\n");
            return;
        }

        ParsedParty parsed = parsePartyContent(content, false);
        
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escapeXml(truncate(extractName(parsed.getName()), MAX_NAME_LEN))).append("</Nm>\n");
        
        boolean hasAddress = !parsed.getAddressLines().isEmpty() || (parsed.getCountry() != null && !parsed.getCountry().isBlank());
        if (hasAddress) {
            xml.append("        <PstlAdr>\n");
            if (parsed.getCountry() != null && !parsed.getCountry().isBlank()) {
                xml.append("          <Ctry>").append(escapeXml(parsed.getCountry())).append("</Ctry>\n");
            }
            if (!parsed.getAddressLines().isEmpty()) {
                String townName = parsed.getAddressLines().get(parsed.getAddressLines().size() - 1);
                xml.append("          <TwnNm>").append(escapeXml(truncate(townName, 35))).append("</TwnNm>\n");
                for (int i = 0; i < parsed.getAddressLines().size() - 1; i++) {
                    xml.append("          <AdrLine>").append(escapeXml(truncate(parsed.getAddressLines().get(i), MAX_ADR_LINE_LEN))).append("</AdrLine>\n");
                }
            }
            xml.append("        </PstlAdr>\n");
        }
        xml.append("      </Dbtr>\n");

        if (parsed.getAccount() != null && !parsed.getAccount().isBlank()) {
            String cleanAcct = cleanAccount(parsed.getAccount());
            xml.append("      <DbtrAcct>\n");
            xml.append("        <Id>\n");
            if (isValidIBAN(cleanAcct)) {
                xml.append("          <IBAN>").append(escapeXml(cleanAcct)).append("</IBAN>\n");
            } else {
                xml.append("          <Othr>\n");
                xml.append("            <Id>").append(escapeXml(cleanAcct)).append("</Id>\n");
                xml.append("          </Othr>\n");
            }
            xml.append("        </Id>\n");
            xml.append("      </DbtrAcct>\n");
        }
    }

    private void appendCreditor(StringBuilder xml, Map<String, String> tags) {
        String content = null;
        String[] variants = {"59", "59A", "59F"};
        for (String v : variants) {
            if (tags.containsKey(v)) {
                content = tags.get(v);
                break;
            }
        }

        if (content == null) {
            xml.append("        <Cdtr>\n          <Nm>UNKNOWN CREDITOR</Nm>\n        </Cdtr>\n");
            return;
        }

        ParsedParty parsed = parsePartyContent(content, false);

        xml.append("        <Cdtr>\n");
        xml.append("          <Nm>").append(escapeXml(truncate(extractName(parsed.getName()), MAX_NAME_LEN))).append("</Nm>\n");
        
        // Extract Town Name and Country to dedicated elements for Nov 2026 mandates
        boolean hasAddress = !parsed.getAddressLines().isEmpty() || (parsed.getCountry() != null && !parsed.getCountry().isBlank());
        if (hasAddress) {
            xml.append("          <PstlAdr>\n");
            if (parsed.getCountry() != null && !parsed.getCountry().isBlank()) {
                xml.append("            <Ctry>").append(escapeXml(parsed.getCountry())).append("</Ctry>\n");
            }
            
            // For structured address mandates, attempt to extract TwnNm if we have address lines
            if (!parsed.getAddressLines().isEmpty()) {
                // Usually Town Name is the last line or before country
                String townName = parsed.getAddressLines().get(parsed.getAddressLines().size() - 1);
                xml.append("            <TwnNm>").append(escapeXml(truncate(townName, 35))).append("</TwnNm>\n");
                
                // Add remaining lines as AdrLine
                for (int i = 0; i < parsed.getAddressLines().size() - 1; i++) {
                    xml.append("            <AdrLine>").append(escapeXml(truncate(parsed.getAddressLines().get(i), MAX_ADR_LINE_LEN))).append("</AdrLine>\n");
                }
            }
            xml.append("          </PstlAdr>\n");
        }
        xml.append("        </Cdtr>\n");

        if (parsed.getAccount() != null && !parsed.getAccount().isBlank()) {
            String cleanAcct = cleanAccount(parsed.getAccount());
            xml.append("        <CdtrAcct>\n");
            xml.append("        <Id>\n");
            if (isValidIBAN(cleanAcct)) {
                xml.append("          <IBAN>").append(escapeXml(cleanAcct)).append("</IBAN>\n");
            } else {
                xml.append("          <Othr>\n");
                xml.append("            <Id>").append(escapeXml(cleanAcct)).append("</Id>\n");
                xml.append("          </Othr>\n");
            }
            xml.append("        </Id>\n");
            xml.append("        </CdtrAcct>\n");
        }
    }

    private void appendAmount(StringBuilder xml, Map<String, String> tags) {
        // Tag 32B format: Currency (3!a) Amount (15d) e.g., EUR1000,50
        String tag32B = tags.get("32B");
        if (tag32B != null && tag32B.length() >= 3) {
            String ccy = tag32B.substring(0, 3);
            String amtStr = tag32B.substring(3);
            String amt = normalizeAmount(amtStr);
            xml.append("        <Amt>\n");
            xml.append("          <InstdAmt Ccy=\"").append(escapeXml(ccy)).append("\">").append(amt).append("</InstdAmt>\n");
            xml.append("        </Amt>\n");
        } else {
            // Fallback
            xml.append("        <Amt>\n");
            xml.append("          <InstdAmt Ccy=\"USD\">0.00</InstdAmt>\n");
            xml.append("        </Amt>\n");
        }
    }

    private String sanitizeId(String id) {
        if (id == null) return "UNKNOWN";
        String clean = id.replaceAll("[^a-zA-Z0-9\\-]", "");
        if (clean.isEmpty()) return "UNKNOWN";
        return truncate(clean, MAX_ID_LEN);
    }

    @Override
    protected String getXsdPath() {
        return "xsd/pain.001.001.09.xsd";
    }
}
