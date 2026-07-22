package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class Pacs008Generator extends BaseMxGenerator {

    @Override
    public boolean supports(String mtType) {
        return "103".equals(mtType);
    }

    @Override
    protected void validateInput(MtMessage mtMessage) {
        if (!mtMessage.getTags().containsKey("20")) {
            throw new IllegalArgumentException("MT103 must have a Transaction Reference Number (:20:)");
        }

        // CBPR+ Mandatory Presence Rules (Advisory Only)
        java.util.List<String> errors = com.mtsaas.backend.domain.CbprValidator.validatePacs008(mtMessage);
        if (!errors.isEmpty()) {
            System.err.println("CBPR+ Validation Advisory: " + String.join(", ", errors));
            // No longer throwing Exception to allow conversion for minimal MT messages
        }
    }

    @Override
    protected String getXsdPath() {
        return "xsd/pacs.008.001.08.xsd";
    }

    @Override
    protected String generateXml(MtMessage mtMessage) {
        Map<String, String> tags = mtMessage.getTags();
        StringBuilder xml = new StringBuilder();

        String msgId = tags.getOrDefault("20", "UNKNOWN-" + System.currentTimeMillis());
        String creationDate = LocalDateTime.now().toString(); // ISO format: 2023-10-05T10:00:00...

        // Ensure creationDate is formatted correctly for XML (remove nanoseconds if too
        // long, etc? Standard toString is usually OK)

        String bizMsgIdr = msgId;

        // --- UETR: preserve if valid v4 UUID, otherwise generate a compliant UUIDv4
        String uetr = tags.get("121");
        final String uuidV4Regex = "[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}";
        if (uetr != null) {
            uetr = uetr.trim().toLowerCase();
        }
        if (uetr == null || uetr.isBlank() || !uetr.matches(uuidV4Regex)) {
            uetr = UUID.randomUUID().toString();
        }

        // --- AppHdr ---
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        // We wrap AppHdr and Document in a RequestPayload like wrapper or just output
        // them sequentially.
        // Standard is often just XML declaration followed by AppHdr then Document.
        // However, invalid XML if multiple root nodes without a wrapper.
        // Let's create a RequestPayload wrapper to be safe and valid XML.
        xml.append("<RequestPayload>\n");

        xml.append("  <AppHdr xmlns=\"urn:iso:std:iso:20022:tech:xsd:head.001.001.01\">\n");
        xml.append("    <CharSet>utf-8</CharSet>\n");
        xml.append("    <Fr>\n");
        xml.append("      <FIId>\n");
        xml.append("        <FinInstnId>\n");
        String senderBic = mtMessage.getSender();
        if (senderBic == null)
            senderBic = tags.get("52A");
        if (senderBic == null)
            senderBic = "UNDEFINED";
        xml.append("          <BICFI>").append(escapeXml(sanitizeBic(senderBic))).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </FIId>\n");
        xml.append("    </Fr>\n");
        xml.append("    <To>\n");
        xml.append("      <FIId>\n");
        xml.append("        <FinInstnId>\n");
        String receiverBic = resolveReceiverBic(mtMessage, tags);
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
        xml.append("        <NbOfTxs>1</NbOfTxs>\n");
        xml.append("        <SttlmInf>\n");
        xml.append("          <SttlmMtd>").append(escapeXml(getSettlementMethod(tags))).append("</SttlmMtd>\n");
        xml.append("        </SttlmInf>\n");
        xml.append("      </GrpHdr>\n");

        /* -------------------- TRANSACTION -------------------- */
        xml.append("      <CdtTrfTxInf>\n");

        /* ---------- Payment Identification ---------- */
        xml.append("        <PmtId>\n");
        xml.append("          <InstrId>").append(escapeXml(msgId)).append("</InstrId>\n");
        xml.append("          <EndToEndId>").append(escapeXml(msgId)).append("</EndToEndId>\n"); // Usually same as TRN
                                                                                                 // or ref

        String txId = tags.get("108");
        if (txId != null && !txId.isBlank()) {
            xml.append("          <TxId>").append(escapeXml(txId.trim())).append("</TxId>\n");
        }

        if (uetr != null && !uetr.isBlank()) {
            xml.append("          <UETR>").append(escapeXml(uetr)).append("</UETR>\n");
        }

        xml.append("        </PmtId>\n");

        /* ---------- Payment Type Information ---------- */
        xml.append("        <PmtTpInf>\n");
        String f23B = tags.get("23B");
        if (f23B != null) {
            String v23 = f23B.trim().toUpperCase();
            switch (v23) {
                case "CRED":
                    xml.append("          <InstrPrty>NORM</InstrPrty>\n");
                    break;
                case "SDVA":
                    xml.append("          <SvcLvl><Cd>SDVA</Cd></SvcLvl>\n");
                    xml.append("          <InstrPrty>HIGH</InstrPrty>\n");
                    break;
                case "SPAY":
                    xml.append("          <SvcLvl><Cd>URGP</Cd></SvcLvl>\n");
                    xml.append("          <InstrPrty>HIGH</InstrPrty>\n");
                    break;
                case "SPRI":
                    xml.append("          <InstrPrty>HIGH</InstrPrty>\n");
                    break;
                case "OTHR":
                    xml.append("          <CtgyPurp><Prtry>OTHR</Prtry></CtgyPurp>\n");
                    break;
                default:
                    xml.append("          <InstrPrty>NORM</InstrPrty>\n");
            }
        } else {
            xml.append("          <InstrPrty>NORM</InstrPrty>\n");
        }
        xml.append("        </PmtTpInf>\n");

        /* ---------- Amount ---------- */
        String field32A = tags.get("32A");
        String f33B = tags.get("33B");
        if (field32A != null && field32A.length() >= 9) {
            String dateRaw = field32A.substring(0, 6);
            String ccy = field32A.substring(6, 9).toUpperCase();
            String amount = extractAmount(field32A);

            String isoDate = "20" + dateRaw.substring(0, 2) + "-" +
                    dateRaw.substring(2, 4) + "-" +
                    dateRaw.substring(4, 6);

            xml.append("        <IntrBkSttlmAmt Ccy=\"").append(escapeXml(ccy)).append("\">")
                    .append(escapeXml(amount)).append("</IntrBkSttlmAmt>\n");
            xml.append("        <IntrBkSttlmDt>").append(escapeXml(isoDate)).append("</IntrBkSttlmDt>\n");

            if (f33B != null && !f33B.isBlank()) {
                String ccy33 = f33B.length() >= 3 ? f33B.substring(0, 3).toUpperCase() : ccy;
                String amt33 = f33B.length() > 3 ? normalizeAmount(f33B.substring(3)) : amount;
                xml.append("        <InstdAmt Ccy=\"").append(escapeXml(ccy33)).append("\">")
                        .append(escapeXml(amt33)).append("</InstdAmt>\n");
            } else {
                xml.append("        <InstdAmt Ccy=\"").append(escapeXml(ccy)).append("\">")
                        .append(escapeXml(amount)).append("</InstdAmt>\n");
            }

        } else {
            xml.append("        <IntrBkSttlmAmt Ccy=\"XXX\">0.00</IntrBkSttlmAmt>\n");
        }

        String f36 = tags.get("36");
        if (f36 != null && !f36.isBlank()) {
            xml.append("        <XchgRate>").append(escapeXml(normalizeRate(f36))).append("</XchgRate>\n");
        }

        String field71A = tags.get("71A");
        xml.append("        <ChrgBr>").append(escapeXml(mapChrgBr(field71A))).append("</ChrgBr>\n");

        // --- Instigating and Instructed Agents (Mandatory in many Market Practices)
        // ---
        // Instigating Agent = Message Sender
        xml.append("        <InstgAgt>\n");
        xml.append("          <FinInstnId>\n");
        xml.append("            <BICFI>").append(escapeXml(sanitizeBic(mtMessage.getSender()))).append("</BICFI>\n");
        xml.append("          </FinInstnId>\n");
        xml.append("        </InstgAgt>\n");

        // Instructed Agent = Tag 57 or Message Receiver (Fallback)
        if (hasAnyTag(tags, "57")) {
            appendAgent(xml, "InstdAgt", null, tags, "57");
        } else {
            xml.append("        <InstdAgt>\n");
            xml.append("          <FinInstnId>\n");
            xml.append("            <BICFI>").append(escapeXml(sanitizeBic(resolveReceiverBic(mtMessage, tags))))
                    .append("</BICFI>\n");
            xml.append("          </FinInstnId>\n");
            xml.append("        </InstdAgt>\n");
        }

        // --- Agents and Parties Sequence for pacs.008 (Mapping per user request) ---

        // 1. Intermediaries
        // 53a (Sender's Correspondent) -> IntrmyAgt1
        if (hasAnyTag(tags, "53")) {
            appendAgent(xml, "IntrmyAgt1", "IntrmyAgt1Acct", tags, "53");
            // 56a (Intermediary) -> IntrmyAgt2
            if (hasAnyTag(tags, "56")) {
                appendAgent(xml, "IntrmyAgt2", "IntrmyAgt2Acct", tags, "56");
            }
        } else {
            // If 53 is missing, 56a (Intermediary) -> IntrmyAgt1
            if (hasAnyTag(tags, "56")) {
                appendAgent(xml, "IntrmyAgt1", "IntrmyAgt1Acct", tags, "56");
            }
        }

        // 2. Debtor (50a)
        appendParty(xml, "Dbtr", "DbtrAcct", tags, "50", "50A", "50K", "50F");

        // 3. Debtor Agent (52a) -> DbtrAgt
        boolean hasDbtrAgt = false;
        if (hasAnyTag(tags, "52")) {
            hasDbtrAgt = appendAgent(xml, "DbtrAgt", "DbtrAgtAcct", tags, "52");
        }
        if (!hasDbtrAgt) {
            xml.append("        <DbtrAgt>\n");
            xml.append("          <FinInstnId>\n");
            xml.append("            <BICFI>").append(escapeXml(sanitizeBic(mtMessage.getSender())))
                    .append("</BICFI>\n");
            xml.append("          </FinInstnId>\n");
            xml.append("        </DbtrAgt>\n");
        }

        // 4. Creditor Agent (57a)
        boolean hasCdtrAgt = false;
        if (hasAnyTag(tags, "57")) {
            hasCdtrAgt = appendAgent(xml, "CdtrAgt", "CdtrAgtAcct", tags, "57");
        }
        if (!hasCdtrAgt) {
            xml.append("        <CdtrAgt>\n");
            xml.append("          <FinInstnId>\n");
            xml.append("            <BICFI>").append(escapeXml(sanitizeBic(resolveReceiverBic(mtMessage, tags))))
                    .append("</BICFI>\n");
            xml.append("          </FinInstnId>\n");
            xml.append("        </CdtrAgt>\n");
        }

        // 5. Creditor (59a)
        appendParty(xml, "Cdtr", "CdtrAcct", tags, "59", "59A", "59F");

        // Field 72 (Sender to Receiver Info)
        String field72 = tags.get("72");
        if (field72 != null && !field72.isBlank()) {
            String[] rawLines = field72.split("\\r?\\n");
            StringBuilder currentInstr = new StringBuilder();
            for (String line : rawLines) {
                if (line == null)
                    continue;
                line = line.trim();
                if (line.isEmpty())
                    continue;
                if (line.startsWith("/") && !line.startsWith("//")) {
                    if (currentInstr.length() > 0) {
                        xml.append("        <InstrForNxtAgt>\n");
                        xml.append("          <InstrInf>").append(escapeXml(currentInstr.toString().trim()))
                                .append("</InstrInf>\n");
                        xml.append("        </InstrForNxtAgt>\n");
                        currentInstr.setLength(0);
                    }
                    currentInstr.append(line);
                } else {
                    String cont = line.replaceFirst("^//+", "").trim();
                    if (currentInstr.length() > 0)
                        currentInstr.append(" ");
                    currentInstr.append(cont);
                }
            }
            if (currentInstr.length() > 0) {
                xml.append("        <InstrForNxtAgt>\n");
                xml.append("          <InstrInf>").append(escapeXml(currentInstr.toString().trim()))
                        .append("</InstrInf>\n");
                xml.append("        </InstrForNxtAgt>\n");
            }
        }

        // Field 70 (Remittance Info)
        String field70 = tags.get("70");
        if (field70 != null && !field70.isBlank()) {
            xml.append("        <RmtInf>\n");
            xml.append("          <Ustrd>").append(escapeXml(field70)).append("</Ustrd>\n");
            xml.append("        </RmtInf>\n");
        }

        xml.append("      </CdtTrfTxInf>\n");
        xml.append("    </FIToFICstmrCdtTrf>\n");
        xml.append("  </Document>\n");
        xml.append("</RequestPayload>\n");

        return xml.toString();
    }

    /* -------------------- Helpers -------------------- */

    /* -------------------- Helpers -------------------- */

    private String normalizeRate(String raw) {
        if (raw == null)
            return "0";
        String s = raw.trim().replace(" ", "").replace(",", ".");
        if (s.isEmpty())
            return "0";
        if (s.endsWith("."))
            s += "0";
        return s;
    }

    private String mapChrgBr(String tag71A) {
        if (tag71A == null || tag71A.isBlank())
            return "SHAR";
        String s = tag71A.trim().toUpperCase();
        if (s.startsWith("OUR")) {
            return "DEBT";
        }
        if (s.startsWith("BEN")) {
            return "CRED";
        }
        if (s.startsWith("SHA") || s.startsWith("SHAR")) {
            return "SHAR";
        }
        return "SHAR";
    }

    private String resolveReceiverBic(MtMessage mtMessage, Map<String, String> tags) {
        if (mtMessage.getReceiver() != null && !mtMessage.getReceiver().isBlank()) {
            return mtMessage.getReceiver();
        }
        if (tags.get("57A") != null && !tags.get("57A").isBlank()) {
            return tags.get("57A");
        }
        if (tags.get("58A") != null && !tags.get("58A").isBlank()) {
            return tags.get("58A");
        }
        return "UNDEFINED";
    }
}
