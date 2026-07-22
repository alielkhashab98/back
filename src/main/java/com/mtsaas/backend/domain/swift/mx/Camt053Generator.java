package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class Camt053Generator extends BaseMxGenerator {

    @Override
    public boolean supports(String mtType) {
        return "940".equals(mtType);
    }

    @Override
    protected void validateInput(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();

        if (!tags.containsKey("20")) {
            throw new IllegalArgumentException("MT940 must have a Transaction Reference Number (:20:)");
        }
        if (!tags.containsKey("25")) {
            throw new IllegalArgumentException("MT940 must have an Account Identification (:25:)");
        }
        if (!tags.containsKey("28C")) {
            throw new IllegalArgumentException("MT940 must have a Statement Number/Sequence (:28C:)");
        }
        if (!tags.containsKey("60F") && !tags.containsKey("60M")) {
            throw new IllegalArgumentException("MT940 must have an Opening Balance (:60F: or :60M:)");
        }
        if (!tags.containsKey("62F") && !tags.containsKey("62M")) {
            throw new IllegalArgumentException("MT940 must have a Closing Balance (:62F: or :62M:)");
        }
    }

    @Override
    protected String getXsdPath() {
        return "xsd/camt.053.001.08.xsd";
    }

    @Override
    public String generate(MtMessage mtMessage) {
        validateInput(mtMessage);
        return generateXml(mtMessage);
    }

    @Override
    protected String generateXml(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        StringBuilder xml = new StringBuilder();

        String msgId = tags.getOrDefault("20", "UNKNOWN-" + System.currentTimeMillis());
        String creationDate = LocalDateTime.now().toString();

        // --- Wrapper & Document ---
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n");
        xml.append("  <BkToCstmrStmt>\n");

        /* -------------------- GROUP HEADER -------------------- */
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(escapeXml(creationDate)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");

        /* -------------------- STATEMENT -------------------- */
        xml.append("    <Stmt>\n");

        /* ---------- Statement Identification ---------- */
        String tag28C = tags.get("28C");
        String stmtNum = "1";
        String seqNum = "1";
        if (tag28C != null && tag28C.contains("/")) {
            String[] parts = tag28C.split("/");
            stmtNum = parts[0];
            if (parts.length > 1) {
                seqNum = parts[1];
            }
        } else if (tag28C != null) {
            stmtNum = tag28C;
        }

        xml.append("      <Id>").append(escapeXml(stmtNum)).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>").append(escapeXml(seqNum)).append("</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(escapeXml(creationDate)).append("</CreDtTm>\n");

        /* ---------- Account Identification (Tag 25) ---------- */
        String tag25 = tags.get("25");
        if (tag25 != null) {
            xml.append("      <Acct>\n");

            // Tag 25 format: [/account] or [BIC/account]
            String accountId = tag25;
            if (tag25.contains("/")) {
                String[] parts = tag25.split("/", 2);
                accountId = parts.length > 1 ? parts[1] : parts[0];
            }

            xml.append("        <Id>\n");
            xml.append("          <Othr>\n");
            xml.append("            <Id>").append(escapeXml(accountId.trim())).append("</Id>\n");
            xml.append("          </Othr>\n");
            xml.append("        </Id>\n");
            xml.append("      </Acct>\n");
        }

        /* ---------- Opening Balance (Tag 60F/60M) ---------- */
        String tag60F = tags.get("60F");
        String tag60M = tags.get("60M");
        String openingBalance = tag60F != null ? tag60F : tag60M;

        if (openingBalance != null) {
            BalanceInfo openBal = parseBalance(openingBalance);
            xml.append("      <Bal>\n");
            xml.append("        <Tp>\n");
            xml.append("          <CdOrPrtry>\n");
            xml.append("            <Cd>OPBD</Cd>\n"); // Opening Booked
            xml.append("          </CdOrPrtry>\n");
            xml.append("        </Tp>\n");
            xml.append("        <Amt Ccy=\"").append(escapeXml(openBal.currency)).append("\">")
                    .append(escapeXml(openBal.amount)).append("</Amt>\n");
            xml.append("        <CdtDbtInd>").append(openBal.creditDebit).append("</CdtDbtInd>\n");
            xml.append("        <Dt>\n");
            xml.append("          <Dt>").append(escapeXml(openBal.date)).append("</Dt>\n");
            xml.append("        </Dt>\n");
            xml.append("      </Bal>\n");
        }

        /* ---------- Closing Balance (Tag 62F/62M) ---------- */
        String tag62F = tags.get("62F");
        String tag62M = tags.get("62M");
        String closingBalance = tag62F != null ? tag62F : tag62M;

        if (closingBalance != null) {
            BalanceInfo closeBal = parseBalance(closingBalance);
            xml.append("      <Bal>\n");
            xml.append("        <Tp>\n");
            xml.append("          <CdOrPrtry>\n");
            xml.append("            <Cd>CLBD</Cd>\n"); // Closing Booked
            xml.append("          </CdOrPrtry>\n");
            xml.append("        </Tp>\n");
            xml.append("        <Amt Ccy=\"").append(escapeXml(closeBal.currency)).append("\">")
                    .append(escapeXml(closeBal.amount)).append("</Amt>\n");
            xml.append("        <CdtDbtInd>").append(closeBal.creditDebit).append("</CdtDbtInd>\n");
            xml.append("        <Dt>\n");
            xml.append("          <Dt>").append(escapeXml(closeBal.date)).append("</Dt>\n");
            xml.append("        </Dt>\n");
            xml.append("      </Bal>\n");
        }

        /* ---------- Statement Lines (Tag 61) ---------- */
        // MT940 can have multiple :61: tags for transactions
        // For simplicity, we'll handle a single transaction for now
        // In a production system, you'd need to parse multiple :61: tags
        String tag61 = tags.get("61");
        if (tag61 != null && !tag61.isBlank()) {
            StatementLine line = parseStatementLine(tag61);

            xml.append("      <Ntry>\n");
            xml.append("        <Amt Ccy=\"").append(escapeXml(line.currency)).append("\">")
                    .append(escapeXml(line.amount)).append("</Amt>\n");
            xml.append("        <CdtDbtInd>").append(line.creditDebit).append("</CdtDbtInd>\n");
            xml.append("        <Sts>\n");
            xml.append("          <Cd>BOOK</Cd>\n"); // Booked
            xml.append("        </Sts>\n");

            if (line.valueDate != null) {
                xml.append("        <ValDt>\n");
                xml.append("          <Dt>").append(escapeXml(line.valueDate)).append("</Dt>\n");
                xml.append("        </ValDt>\n");
            }

            if (line.bookingDate != null) {
                xml.append("        <BookgDt>\n");
                xml.append("          <Dt>").append(escapeXml(line.bookingDate)).append("</Dt>\n");
                xml.append("        </BookgDt>\n");
            }

            // Information to Account Owner (Tag 86)
            String tag86 = tags.get("86");
            if (tag86 != null && !tag86.isBlank()) {
                xml.append("        <NtryDtls>\n");
                xml.append("          <TxDtls>\n");
                xml.append("            <RmtInf>\n");
                xml.append("              <Ustrd>").append(escapeXml(tag86.trim())).append("</Ustrd>\n");
                xml.append("            </RmtInf>\n");
                xml.append("          </TxDtls>\n");
                xml.append("        </NtryDtls>\n");
            }

            xml.append("      </Ntry>\n");
        }

        xml.append("    </Stmt>\n");
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>\n");

        return xml.toString();
    }

    /**
     * Parse balance field (60F/60M/62F/62M)
     * Format: [C/D][YYMMDD][Currency][Amount]
     * Example: C231204USD1000,00
     */
    private BalanceInfo parseBalance(String balanceField) {
        BalanceInfo info = new BalanceInfo();

        if (balanceField == null || balanceField.length() < 10) {
            throw new IllegalArgumentException("Invalid balance field: " + balanceField);
        }

        // Credit/Debit indicator
        char cdIndicator = balanceField.charAt(0);
        info.creditDebit = (cdIndicator == 'C') ? "CRDT" : "DBIT";

        // Date (YYMMDD)
        String dateStr = balanceField.substring(1, 7);
        info.date = "20" + dateStr.substring(0, 2) + "-" + dateStr.substring(2, 4) + "-" + dateStr.substring(4, 6);

        // Currency (3 chars)
        info.currency = balanceField.substring(7, 10);

        // Amount (rest of the string)
        info.amount = normalizeAmount(balanceField.substring(10));

        return info;
    }

    /**
     * Parse statement line (Tag 61)
     * Format: [YYMMDD][MMDD][C/D][Amount][Transaction Type][Reference]
     * Example: 2312041204C1000,00NTRFNONREF
     */
    private StatementLine parseStatementLine(String lineField) {
        StatementLine line = new StatementLine();

        if (lineField == null || lineField.length() < 10) {
            // Return minimal valid line
            line.amount = "0.00";
            line.currency = "USD";
            line.creditDebit = "CRDT";
            return line;
        }

        try {
            // Value date (YYMMDD)
            String valueDateStr = lineField.substring(0, 6);
            line.valueDate = "20" + valueDateStr.substring(0, 2) + "-" +
                    valueDateStr.substring(2, 4) + "-" + valueDateStr.substring(4, 6);

            int pos = 6;

            // Optional entry date (MMDD)
            if (lineField.length() > pos + 4 && Character.isDigit(lineField.charAt(pos))) {
                String entryDateStr = lineField.substring(pos, pos + 4);
                line.bookingDate = "20" + valueDateStr.substring(0, 2) + "-" +
                        entryDateStr.substring(0, 2) + "-" + entryDateStr.substring(2, 4);
                pos += 4;
            }

            // Credit/Debit indicator
            if (lineField.length() > pos) {
                char cdIndicator = lineField.charAt(pos);
                line.creditDebit = (cdIndicator == 'C' || cdIndicator == 'R') ? "CRDT" : "DBIT";
                pos++;
            }

            // Amount - find where it ends (usually before transaction type code)
            StringBuilder amountStr = new StringBuilder();
            while (pos < lineField.length() && (Character.isDigit(lineField.charAt(pos)) ||
                    lineField.charAt(pos) == ',' || lineField.charAt(pos) == '.')) {
                amountStr.append(lineField.charAt(pos));
                pos++;
            }

            line.amount = normalizeAmount(amountStr.toString());
            line.currency = "USD"; // Default, could be extracted from context

        } catch (Exception e) {
            // Fallback to safe defaults
            line.amount = "0.00";
            line.currency = "USD";
            line.creditDebit = "CRDT";
        }

        return line;
    }

    private static class BalanceInfo {
        String creditDebit;
        String date;
        String currency;
        String amount;
    }

    private static class StatementLine {
        String valueDate;
        String bookingDate;
        String creditDebit;
        String currency;
        String amount;
    }
}
