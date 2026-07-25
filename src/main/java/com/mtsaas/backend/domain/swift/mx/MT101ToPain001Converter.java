package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import com.mtsaas.backend.domain.swift.mt.MtParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service class that translates legacy SWIFT MT101 payment initiation instructions
 * into ISO 20022 pain.001.001.09 XML payloads in strict accordance with XSD sequence rules.
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
     *
     * @param mtMessage the parsed MT101 message
     * @return the generated ISO 20022 XML
     */
    public String convert(MtMessage mtMessage) {
        return generate(mtMessage);
    }

    private void validateInstructionCount(String rawMt101) {
        if (rawMt101 == null) return;
        
        // Sequence B in MT101 contains transfer instructions.
        // Each instruction starts with tag :21: (Transaction Reference).
        Matcher matcher = Pattern.compile("(?m)^:21:").matcher(rawMt101);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        
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

        String msgId = tags.getOrDefault("20", "UNKNOWN_MSG_ID").trim();
        msgId = escapeXml(sanitizeId(msgId));

        // CR 3014 Guardrail: Enforce PmtInfId matches MsgId
        String pmtInfId = msgId;

        // Dynamic Creation Timestamp
        String creationDateTime = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        // Dynamic Requested Execution Date (from Tag 30 YYMMDD/YYYYMMDD or current UTC date)
        String reqdExctnDt = parseExecutionDate(tags.get("30"));

        // Dynamic Initiating Party Name (from Debtor tag 50 or Sender BIC)
        String initgPtyName = extractInitiatingPartyName(mtMessage);

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");
        
        // --- 1. Group Header (GrpHdr) ---
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(creationDateTime).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <InitgPty>\n");
        xml.append("        <Nm>").append(escapeXml(truncate(initgPtyName, MAX_NAME_LEN))).append("</Nm>\n");
        xml.append("      </InitgPty>\n");
        xml.append("    </GrpHdr>\n");

        // --- 2. Payment Information (PaymentInstruction30 / PmtInf) ---
        // XSD Order: PmtInfId -> PmtMtd -> ReqdExctnDt -> Dbtr -> DbtrAcct -> DbtrAgt -> CdtTrfTxInf
        xml.append("    <PmtInf>\n");
        xml.append("      <PmtInfId>").append(pmtInfId).append("</PmtInfId>\n");
        xml.append("      <PmtMtd>TRF</PmtMtd>\n");
        xml.append("      <ReqdExctnDt>\n");
        xml.append("        <Dt>").append(reqdExctnDt).append("</Dt>\n");
        xml.append("      </ReqdExctnDt>\n");

        // Debtor (Tag 50 series)
        appendDebtor(xml, tags);

        // Debtor Agent (Mandatory in PaymentInstruction30)
        appendDebtorAgent(xml, mtMessage);

        // --- 3. Credit Transfer Transaction Information (CreditTransferTransaction34 / CdtTrfTxInf) ---
        // XSD Order: PmtId -> Amt -> ChrgBr -> CdtrAgt -> Cdtr -> CdtrAcct -> RmtInf
        xml.append("      <CdtTrfTxInf>\n");
        xml.append("        <PmtId>\n");
        String instrId = tags.getOrDefault("21", msgId).trim();
        xml.append("          <InstrId>").append(escapeXml(sanitizeId(instrId))).append("</InstrId>\n");
        xml.append("          <EndToEndId>").append(escapeXml(sanitizeId(instrId))).append("</EndToEndId>\n");
        xml.append("        </PmtId>\n");

        // Amount (Tag 32B)
        appendAmount(xml, tags);

        // Charge Bearer (Tag 71A if present)
        appendChargeBearer(xml, tags);

        // Creditor Agent (Tag 57A if present)
        appendAgent(xml, "CdtrAgt", "CdtrAgtAcct", tags, "57");

        // Creditor & Creditor Account (Tag 59 series)
        appendCreditor(xml, tags);

        // Remittance Info (Tag 70 if present)
        appendRemittanceInfo(xml, tags);

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
            xml.append("      <Dbtr>\n        <Nm>UNKNOWN DEBTOR</Nm>\n      </Dbtr>\n");
            return;
        }

        ParsedParty parsed = parsePartyContent(content, false);
        
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escapeXml(truncate(extractName(parsed.getName()), MAX_NAME_LEN))).append("</Nm>\n");
        appendPstlAdr(xml, "        ", parsed.getAddressLines(), parsed.getCountry());
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

    private void appendDebtorAgent(StringBuilder xml, MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        String bic = null;
        if (tags.containsKey("51A")) {
            bic = tags.get("51A");
        } else if (tags.containsKey("52A")) {
            bic = tags.get("52A");
        } else if (mtMessage.getSender() != null && !mtMessage.getSender().isBlank()) {
            bic = mtMessage.getSender();
        }

        String cleanBic = sanitizeBic(bic);

        xml.append("      <DbtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        xml.append("          <BICFI>").append(escapeXml(cleanBic)).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </DbtrAgt>\n");
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
        appendPstlAdr(xml, "          ", parsed.getAddressLines(), parsed.getCountry());
        xml.append("        </Cdtr>\n");

        if (parsed.getAccount() != null && !parsed.getAccount().isBlank()) {
            String cleanAcct = cleanAccount(parsed.getAccount());
            xml.append("        <CdtrAcct>\n");
            xml.append("          <Id>\n");
            if (isValidIBAN(cleanAcct)) {
                xml.append("            <IBAN>").append(escapeXml(cleanAcct)).append("</IBAN>\n");
            } else {
                xml.append("            <Othr>\n");
                xml.append("              <Id>").append(escapeXml(cleanAcct)).append("</Id>\n");
                xml.append("            </Othr>\n");
            }
            xml.append("          </Id>\n");
            xml.append("        </CdtrAcct>\n");
        }
    }

    /**
     * Appends PostalAddress24 adhering strictly to XSD sequence rules:
     * TwnNm (line 819) -> Ctry (line 823) -> AdrLine (line 824).
     */
    private void appendPstlAdr(StringBuilder xml, String indent, List<String> addressLines, String country) {
        if (addressLines.isEmpty() && (country == null || country.isBlank())) {
            return;
        }

        xml.append(indent).append("<PstlAdr>\n");

        String townName = null;
        List<String> adrLines = new ArrayList<>(addressLines);

        if (!adrLines.isEmpty()) {
            townName = adrLines.remove(adrLines.size() - 1);
        }

        if (townName != null && !townName.isBlank()) {
            xml.append(indent).append("  <TwnNm>").append(escapeXml(truncate(townName, 35))).append("</TwnNm>\n");
        }

        if (country != null && !country.isBlank()) {
            xml.append(indent).append("  <Ctry>").append(escapeXml(country)).append("</Ctry>\n");
        }

        for (String adr : adrLines) {
            xml.append(indent).append("  <AdrLine>").append(escapeXml(truncate(adr, MAX_ADR_LINE_LEN))).append("</AdrLine>\n");
        }

        xml.append(indent).append("</PstlAdr>\n");
    }

    private void appendAmount(StringBuilder xml, Map<String, String> tags) {
        String tag32B = tags.get("32B");
        if (tag32B != null && tag32B.length() >= 3) {
            String ccy = tag32B.substring(0, 3);
            String amtStr = tag32B.substring(3);
            String amt = normalizeAmount(amtStr);
            xml.append("        <Amt>\n");
            xml.append("          <InstdAmt Ccy=\"").append(escapeXml(ccy)).append("\">").append(amt).append("</InstdAmt>\n");
            xml.append("        </Amt>\n");
        } else {
            xml.append("        <Amt>\n");
            xml.append("          <InstdAmt Ccy=\"USD\">0.00</InstdAmt>\n");
            xml.append("        </Amt>\n");
        }
    }

    private void appendChargeBearer(StringBuilder xml, Map<String, String> tags) {
        String tag71A = tags.get("71A");
        if (tag71A == null || tag71A.isBlank()) return;

        String code = "SHAR";
        String normalized = tag71A.trim().toUpperCase();
        if (normalized.startsWith("OUR")) code = "DEBT";
        else if (normalized.startsWith("BEN")) code = "CRED";
        else if (normalized.startsWith("SHA")) code = "SHAR";

        xml.append("        <ChrgBr>").append(code).append("</ChrgBr>\n");
    }

    private void appendRemittanceInfo(StringBuilder xml, Map<String, String> tags) {
        String tag70 = tags.get("70");
        if (tag70 != null && !tag70.isBlank()) {
            xml.append("        <RmtInf>\n");
            xml.append("          <Ustrd>").append(escapeXml(truncate(tag70, 140))).append("</Ustrd>\n");
            xml.append("        </RmtInf>\n");
        }
    }

    private String sanitizeId(String id) {
        if (id == null) return "UNKNOWN";
        String clean = id.replaceAll("[^a-zA-Z0-9\\-]", "");
        if (clean.isEmpty()) return "UNKNOWN";
        return truncate(clean, MAX_ID_LEN);
    }

    private String parseExecutionDate(String tag30) {
        if (tag30 != null && !tag30.isBlank()) {
            String clean = tag30.trim();
            // YYMMDD -> 20YY-MM-DD
            if (clean.length() == 6 && clean.matches("\\d{6}")) {
                String yy = clean.substring(0, 2);
                String mm = clean.substring(2, 4);
                String dd = clean.substring(4, 6);
                return "20" + yy + "-" + mm + "-" + dd;
            }
            // YYYYMMDD -> YYYY-MM-DD
            if (clean.length() == 8 && clean.matches("\\d{8}")) {
                String yyyy = clean.substring(0, 4);
                String mm = clean.substring(4, 6);
                String dd = clean.substring(6, 8);
                return yyyy + "-" + mm + "-" + dd;
            }
        }
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    private String extractInitiatingPartyName(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        String[] variants = {"50", "50A", "50F", "50G", "50H", "50K"};
        for (String v : variants) {
            if (tags.containsKey(v)) {
                ParsedParty party = parsePartyContent(tags.get(v), false);
                if (party != null && party.getName() != null && !party.getName().isBlank()) {
                    return party.getName();
                }
            }
        }
        if (mtMessage.getSender() != null && !mtMessage.getSender().isBlank()) {
            return mtMessage.getSender();
        }
        return "INITIATING PARTY";
    }

    @Override
    protected String getXsdPath() {
        return "xsd/pain.001.001.09.xsd";
    }
}
