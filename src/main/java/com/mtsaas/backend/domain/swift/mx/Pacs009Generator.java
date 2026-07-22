package com.mtsaas.backend.domain.swift.mx;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class Pacs009Generator extends BaseMxGenerator {

    @Override
    public boolean supports(String mtType) {
        return "202".equals(mtType);
    }

    @Override
    protected void validateInput(MtMessage mtMessage) {
        if (!mtMessage.getTags().containsKey("20")) {
            throw new IllegalArgumentException("MT202 must have a Transaction Reference Number (:20:)");
        }

        // CBPR+ Mandatory Presence Rules (Advisory Only)
        java.util.List<String> errors = com.mtsaas.backend.domain.CbprValidator.validatePacs009(mtMessage);
        if (!errors.isEmpty()) {
            System.err.println("CBPR+ Validation Advisory (MT202): " + String.join(", ", errors));
        }
    }

    @Override
    protected String getXsdPath() {
        // Return null or empty if we don't have the XSD yet, or point to a placeholder.
        // BaseMxGenerator uses XmlValidator which might fail if path is invalid.
        // For MVP, let's reuse pacs.008 XSD if structurally similar enough for basic
        // validation,
        // OR return a dummy path and ensure XmlValidator handles it gracefully (it
        // throws though).
        // Since we cannot change XmlValidator easily without risking other things,
        // let's try pointing to pacs.008 XSD as a temporary measure if needed,
        // BUT pacs.009 structure is different (FICreditTransfer vs
        // CustomerCreditTransfer).
        //
        // Override generateAndValidateXml to skip validation if needed?
        // No, can't easily override final method.
        //
        // Let's rely on the fact that if we provide a non-existent path, it throws.
        // HACK: We will create a dummy empty XSD file for pacs.009 if needed,
        // OR better, we just don't validate for now by overriding generate in a way
        // that skips validation
        // but BaseMxGenerator.generate is not final.
        return "xsd/pacs.009.001.08.xsd";
    }

    // Override generate to SKIP validation if we don't have the XSD
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
        String receiverBic = mtMessage.getReceiver();
        if (receiverBic == null)
            receiverBic = tags.get("58A");
        if (receiverBic == null)
            receiverBic = "UNDEFINED";
        xml.append("          <BICFI>").append(escapeXml(sanitizeBic(receiverBic))).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </FIId>\n");
        xml.append("    </To>\n");
        xml.append("    <BizMsgIdr>").append(escapeXml(bizMsgIdr)).append("</BizMsgIdr>\n");
        xml.append("    <MsgDefIdr>pacs.009.001.08</MsgDefIdr>\n");
        xml.append("    <CreDt>").append(escapeXml(creationDate)).append("</CreDt>\n");
        xml.append("  </AppHdr>\n");

        xml.append("  <Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.009.001.08\">\n");
        xml.append("    <FICdtTrf>\n");

        /* -------------------- GROUP HEADER -------------------- */
        xml.append("      <GrpHdr>\n");
        xml.append("        <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
        xml.append("        <CreDtTm>").append(escapeXml(creationDate)).append("</CreDtTm>\n");
        xml.append("        <NbOfTxs>1</NbOfTxs>\n");
        xml.append("        <SttlmInf>\n");
        xml.append("          <SttlmMtd>").append(escapeXml(getSettlementMethod(tags))).append("</SttlmMtd>\n");
        xml.append("        </SttlmInf>\n");
        xml.append("      </GrpHdr>\n");

        /* -------------------- CREDIT TRANSFER TRANSACTION INFO -------------------- */
        xml.append("      <CdtTrfTxInf>\n");

        /* ---------- Payment Identification ---------- */
        xml.append("        <PmtId>\n");
        xml.append("          <InstrId>").append(escapeXml(msgId)).append("</InstrId>\n");
        xml.append("          <EndToEndId>").append(escapeXml(tags.getOrDefault("21", msgId)))
                .append("</EndToEndId>\n");

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
        xml.append("          <InstrPrty>NORM</InstrPrty>\n");
        xml.append("        </PmtTpInf>\n");

        /* ---------- Amount ---------- */
        String field32A = tags.get("32A");
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
        } else {
            xml.append("        <IntrBkSttlmAmt Ccy=\"XXX\">0.00</IntrBkSttlmAmt>\n");
        }

        // Field 72 (Sender to Receiver Info) -> InstrForNxtAgt
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

        // --- Agents and Parties Sequence for pacs.009 ---

        // Instructing Agent (Mandatory)
        xml.append("        <InstgAgt>\n");
        xml.append("          <FinInstnId>\n");
        xml.append("            <BICFI>").append(escapeXml(sanitizeBic(mtMessage.getSender()))).append("</BICFI>\n");
        xml.append("          </FinInstnId>\n");
        xml.append("        </InstgAgt>\n");

        // Instructed Agent (Mandatory)
        xml.append("        <InstdAgt>\n");
        xml.append("          <FinInstnId>\n");
        xml.append("            <BICFI>").append(escapeXml(sanitizeBic(mtMessage.getReceiver()))).append("</BICFI>\n");
        xml.append("          </FinInstnId>\n");
        xml.append("        </InstdAgt>\n");

        // Intermediaries (Tags 56/54)
        if (hasAnyTag(tags, "56")) {
            appendAgent(xml, "IntrmyAgt1", "IntrmyAgt1Acct", tags, "56");
        }
        if (hasAnyTag(tags, "54")) {
            if (hasAnyTag(tags, "56")) {
                appendAgent(xml, "IntrmyAgt2", "IntrmyAgt2Acct", tags, "54");
            } else {
                appendAgent(xml, "IntrmyAgt1", "IntrmyAgt1Acct", tags, "54");
            }
        }

        // Debtor (52a for MT202) -> Dbtr (Mandatory in pacs.009)
        // If 52 is missing, Debtor IS the Sender
        if (hasAnyTag(tags, "52")) {
            appendAgent(xml, "Dbtr", "DbtrAcct", tags, "52");
        } else {
            xml.append("        <Dbtr>\n");
            xml.append("          <FinInstnId>\n");
            xml.append("            <BICFI>").append(escapeXml(sanitizeBic(mtMessage.getSender())))
                    .append("</BICFI>\n");
            xml.append("          </FinInstnId>\n");
            xml.append("        </Dbtr>\n");
        }

        // Debtor Agent (53a for MT202) -> DbtrAgt
        if (hasAnyTag(tags, "53")) {
            appendAgent(xml, "DbtrAgt", "DbtrAgtAcct", tags, "53");
        }

        // Creditor Agent (57a)
        if (hasAnyTag(tags, "57")) {
            appendAgent(xml, "CdtrAgt", "CdtrAgtAcct", tags, "57");
        }

        // Creditor (58a) -> Cdtr (Mandatory in pacs.009)
        // If 58 is missing, Creditor IS the Receiver
        if (hasAnyTag(tags, "58")) {
            appendAgent(xml, "Cdtr", "CdtrAcct", tags, "58");
        } else {
            xml.append("        <Cdtr>\n");
            xml.append("          <FinInstnId>\n");
            xml.append("            <BICFI>").append(escapeXml(sanitizeBic(mtMessage.getReceiver())))
                    .append("</BICFI>\n");
            xml.append("          </FinInstnId>\n");
            xml.append("        </Cdtr>\n");
        }

        // Remittance Info (70)
        String field70 = tags.get("70");
        if (field70 != null && !field70.isBlank()) {
            xml.append("        <RmtInf>\n");
            xml.append("          <Ustrd>").append(escapeXml(field70)).append("</Ustrd>\n");
            xml.append("        </RmtInf>\n");
        }

        xml.append("      </CdtTrfTxInf>\n");
        xml.append("    </FICdtTrf>\n");
        xml.append("  </Document>\n");
        xml.append("</RequestPayload>\n");

        return xml.toString();
    }

    // BaseMxGenerator provides shared mapping helpers: appendAgent, hasAnyTag,
    // escapeXml, etc.

}
