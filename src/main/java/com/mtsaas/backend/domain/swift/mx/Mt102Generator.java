package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
public class Mt102Generator extends BaseMxGenerator {

    @Override
    public boolean supports(String mtType) {
        return "102".equals(mtType);
    }

    @Override
    protected void validateInput(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();

        if (!tags.containsKey("20")) {
            throw new IllegalArgumentException("MT102 must have a Sender's Reference (:20:)");
        }

        // CBPR+ Mandatory Presence Rules (Advisory Only)
        java.util.List<String> errors = com.mtsaas.backend.domain.CbprValidator.validatePacs008Bulk(mtMessage);
        if (!errors.isEmpty()) {
            System.err.println("CBPR+ Validation Advisory (Bulk): " + String.join(", ", errors));
        }
    }

    @Override
    protected String getXsdPath() {
        return "xsd/pacs.008.001.08.xsd";
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
        String bizMsgIdr = msgId;

        // Parse multiple transactions from MT102
        List<Transaction> transactions = parseTransactions(tags);
        int txCount = transactions.size();

        // --- UETR ---
        String uetr = tags.get("121");
        final String uuidV4Regex = "[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}";
        if (uetr != null) {
            uetr = uetr.trim().toLowerCase();
        }
        if (uetr == null || uetr.isBlank() || !uetr.matches(uuidV4Regex)) {
            uetr = UUID.randomUUID().toString();
        }

        // --- Wrapper & AppHdr ---
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<RequestPayload>\n");

        xml.append("  <AppHdr xmlns=\"urn:iso:std:iso:20022:tech:xsd:head.001.001.01\">\n");
        xml.append("    <CharSet>utf-8</CharSet>\n");
        xml.append("    <Fr>\n");
        xml.append("      <FIId>\n");
        xml.append("        <FinInstnId>\n");
        String senderBic = mtMessage.getSender();
        if (senderBic == null)
            senderBic = "UNDEFINED";
        xml.append("          <BICFI>").append(escapeXml(sanitizeBic(senderBic))).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </FIId>\n");
        xml.append("    </Fr>\n");
        xml.append("    <To>\n");
        xml.append("      <FIId>\n");
        xml.append("        <FinInstnId>\n");
        String receiverBic = mtMessage.getReceiver();
        if (receiverBic == null)
            receiverBic = "UNDEFINED";
        xml.append("          <BICFI>").append(escapeXml(sanitizeBic(receiverBic))).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </FIId>\n");
        xml.append("    </To>\n");
        xml.append("    <BizMsgIdr>").append(escapeXml(bizMsgIdr)).append("</BizMsgIdr>\n");
        xml.append("    <MsgDefIdr>pacs.008.001.08</MsgDefIdr>\n");
        xml.append("    <CreDt>").append(escapeXml(creationDate)).append("</CreDt>\n");
        xml.append("  </AppHdr>\n");

        xml.append("  <Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">\n");
        xml.append("    <FIToFICstmrCdtTrf>\n");

        /* -------------------- GROUP HEADER -------------------- */
        xml.append("      <GrpHdr>\n");
        xml.append("        <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
        xml.append("        <CreDtTm>").append(escapeXml(creationDate)).append("</CreDtTm>\n");
        xml.append("        <NbOfTxs>").append(txCount).append("</NbOfTxs>\n");
        xml.append("        <SttlmInf>\n");
        xml.append("          <SttlmMtd>").append(escapeXml(getSettlementMethod(tags))).append("</SttlmMtd>\n");
        xml.append("        </SttlmInf>\n");
        xml.append("      </GrpHdr>\n");

        /*
         * -------------------- MULTIPLE CREDIT TRANSFER TRANSACTION INFO
         * --------------------
         */
        for (Transaction tx : transactions) {
            xml.append(generateTransactionXml(tx, uetr, tags));
        }

        xml.append("    </FIToFICstmrCdtTrf>\n");
        xml.append("  </Document>\n");
        xml.append("</RequestPayload>\n");

        return xml.toString();
    }

    private List<Transaction> parseTransactions(Map<String, String> tags) {
        List<Transaction> transactions = new ArrayList<>();

        // For simplicity, we'll handle a single transaction for now
        // In a production system, you'd need to parse the MT102 message to extract
        // multiple :21: (transaction reference) groups

        Transaction tx = new Transaction();
        tx.reference = tags.getOrDefault("21", "TXN-001");
        tx.amount = tags.get("32B"); // Transaction amount
        if (tx.amount == null) {
            // Fallback to total amount if no transaction-specific amount
            tx.amount = tags.get("32A");
        }
        tx.beneficiary = tags.get("59");
        if (tx.beneficiary == null) {
            tx.beneficiary = tags.get("59A");
        }
        tx.remittanceInfo = tags.get("70");

        transactions.add(tx);

        // FUTURE: In a full implementation, parse multiple transaction groups
        // by looking for repeating :21: tags in the original message

        return transactions;
    }

    private String generateTransactionXml(Transaction tx, String uetr, Map<String, String> tags) {
        StringBuilder xml = new StringBuilder();

        xml.append("      <CdtTrfTxInf>\n");

        /* ---------- Payment Identification ---------- */
        xml.append("        <PmtId>\n");
        xml.append("          <InstrId>").append(escapeXml(tx.reference)).append("</InstrId>\n");
        xml.append("          <EndToEndId>").append(escapeXml(tx.reference)).append("</EndToEndId>\n");
        xml.append("          <UETR>").append(escapeXml(uetr)).append("</UETR>\n");
        xml.append("        </PmtId>\n");

        /* ---------- Payment Type Information ---------- */
        xml.append("        <PmtTpInf>\n");
        xml.append("          <InstrPrty>NORM</InstrPrty>\n");
        xml.append("        </PmtTpInf>\n");

        /* ---------- Amount ---------- */
        String amountField = tx.amount;
        if (amountField != null && amountField.length() >= 4) {
            String currency = amountField.substring(0, 3);
            String amount = normalizeAmount(amountField.substring(3));

            xml.append("        <IntrBkSttlmAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                    .append(escapeXml(amount)).append("</IntrBkSttlmAmt>\n");
            xml.append("        <InstdAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                    .append(escapeXml(amount)).append("</InstdAmt>\n");
        }

        /* ---------- Debtor (Ordering Customer - Tag 50K) ---------- */
        appendParty(xml, "Dbtr", "DbtrAcct", tags, "50", "50A", "50K", "50F");

        /* ---------- Creditor (Beneficiary Customer - Tag 59/59A) ---------- */
        if (tx.beneficiary != null) {
            appendParty(xml, "Cdtr", "CdtrAcct", Collections.singletonMap("59", tx.beneficiary), "59", "59");
        }

        /* ---------- Remittance Information ---------- */
        if (tx.remittanceInfo != null && !tx.remittanceInfo.isBlank()) {
            xml.append("        <RmtInf>\n");
            xml.append("          <Ustrd>").append(escapeXml(tx.remittanceInfo.trim())).append("</Ustrd>\n");
            xml.append("        </RmtInf>\n");
        }

        xml.append("      </CdtTrfTxInf>\n");

        return xml.toString();
    }

    private static class Transaction {
        String reference;
        String amount;
        String beneficiary;
        String remittanceInfo;
    }
}
