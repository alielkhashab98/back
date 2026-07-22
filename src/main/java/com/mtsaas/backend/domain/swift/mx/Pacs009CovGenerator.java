package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class Pacs009CovGenerator extends Pacs009Generator {

    @Override
    public boolean supports(String mtType) {
        return "202COV".equals(mtType);
    }

    @Override
    protected void validateInput(MtMessage mtMessage) {
        super.validateInput(mtMessage);

        // CBPR+ Mandatory Presence Rules (Advisory Only)
        java.util.List<String> errors = com.mtsaas.backend.domain.CbprValidator.validatePacs009Cov(mtMessage);
        if (!errors.isEmpty()) {
            System.err.println("CBPR+ Validation Advisory (COV): " + String.join(", ", errors));
        }
    }

    @Override
    protected String generateXml(MtMessage mtMessage) {
        // Get the base pacs.009 XML from parent
        String baseXml = super.generateXml(mtMessage);

        // Insert Sequence B (Underlying Customer Credit Transfer) before closing
        // </CdtTrfTxInf>
        String sequenceB = generateSequenceB(mtMessage);

        // Find the position to insert (before </CdtTrfTxInf>)
        int insertPos = baseXml.lastIndexOf("</CdtTrfTxInf>");
        if (insertPos == -1) {
            throw new RuntimeException("Failed to find </CdtTrfTxInf> in generated XML");
        }

        // Insert Sequence B
        StringBuilder result = new StringBuilder(baseXml);
        result.insert(insertPos, sequenceB);

        return result.toString();
    }

    private String generateSequenceB(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        StringBuilder xml = new StringBuilder();

        xml.append("        <!-- Sequence B: Underlying Customer Credit Transfer -->\n");
        xml.append("        <UndrlygCstmrCdtTrf>\n");

        /* ---------- Underlying Debtor (Ordering Customer - Tag 50a) ---------- */
        appendParty(xml, "Dbtr", "DbtrAcct", tags, "50", "50A", "50K", "50F");

        /* ---------- Underlying Creditor (Beneficiary Customer - Tag 59a) ---------- */
        appendParty(xml, "Cdtr", "CdtrAcct", tags, "59", "59A", "59F");

        /* ---------- Remittance Information (Tag 70) ---------- */
        String tag70 = tags.get("70");
        if (tag70 != null && !tag70.isBlank()) {
            xml.append("          <RmtInf>\n");
            xml.append("            <Ustrd>").append(escapeXml(tag70.trim())).append("</Ustrd>\n");
            xml.append("          </RmtInf>\n");
        }

        /* ---------- Instructed Amount (Tag 33B) ---------- */
        String tag33B = tags.get("33B");
        if (tag33B != null && tag33B.length() >= 4) {
            String currency = tag33B.substring(0, 3);
            String amount = normalizeAmount(tag33B.substring(3));

            xml.append("          <InstdAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                    .append(escapeXml(amount)).append("</InstdAmt>\n");
        }

        xml.append("        </UndrlygCstmrCdtTrf>\n");

        return xml.toString();
    }
}
