package com.mtsaas.backend.domain.swift.mapping;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class FieldMappingRegistry {

    private final Map<String, FieldMappingDefinition> registry = new HashMap<>();

    public FieldMappingRegistry() {
        registerDefinitions();
    }

    private void registerDefinitions() {
        // Tag 20: Transaction Reference Number
        registry.put("20", FieldMappingDefinition.builder()
                .tag("20")
                .name("Sender's Reference / Transaction Reference")
                .defaultMxPath("PmtId/InstrId & GrpHdr/MsgId")
                .explanation("Maps the MT Sender's Reference directly to ISO 20022 Instruction Identification (InstrId) and Group Header Message ID.")
                .defaultWarning(null)
                .build());

        // Tag 21: Related Reference
        registry.put("21", FieldMappingDefinition.builder()
                .tag("21")
                .name("Related Reference")
                .defaultMxPath("PmtId/EndToEndId")
                .explanation("Maps the MT Related Reference to ISO 20022 EndToEndId, maintaining transaction tracking across intermediate financial institutions.")
                .defaultWarning(null)
                .build());

        // Tag 23B: Bank Operation Code
        registry.put("23B", FieldMappingDefinition.builder()
                .tag("23B")
                .name("Bank Operation Code")
                .defaultMxPath("PmtTpInf/LclInstrm/Prtry")
                .explanation("Translates MT bank operation codes (CRED, SPAY, SSTD, SPRI) to ISO 20022 Local Instrument proprietary codes.")
                .defaultWarning(null)
                .build());

        // Tag 25: Account Identification
        registry.put("25", FieldMappingDefinition.builder()
                .tag("25")
                .name("Account Identification / Account Number")
                .defaultMxPath("Stmt/Acct/Id/Othr/Id")
                .explanation("Maps statement account identification in MT940/MT102 to the ISO 20022 Account Identification element.")
                .defaultWarning(null)
                .build());

        // Tag 28D: Message Index / Total
        registry.put("28D", FieldMappingDefinition.builder()
                .tag("28D")
                .name("Message Index / Total")
                .defaultMxPath("GrpHdr/NbOfTxs")
                .explanation("Maps MT message sequence index to ISO 20022 Number of Transactions or statement sequence.")
                .defaultWarning(null)
                .build());

        // Tag 28C: Statement Number / Sequence Number
        registry.put("28C", FieldMappingDefinition.builder()
                .tag("28C")
                .name("Statement Number / Sequence Number")
                .defaultMxPath("Stmt/LglSeqNb")
                .explanation("Maps statement number and sequence to ISO 20022 Legal Sequence Number in camt.053.")
                .defaultWarning(null)
                .build());

        // Tag 32A: Value Date / Currency / Interbank Settled Amount
        registry.put("32A", FieldMappingDefinition.builder()
                .tag("32A")
                .name("Value Date, Currency & Interbank Settled Amount")
                .defaultMxPath("CdtTrfTxInf/IntrBkSttlmAmt & IntrBkSttlmDt")
                .explanation("Parses date (YYMMDD), currency (3-letter ISO), and amount (comma separator). Currency becomes attribute @Ccy, date is formatted as YYYY-MM-DD, and amount uses period decimal point.")
                .defaultWarning("Value date converted from 2-digit YY to 4-digit YYYY format. Comma decimal separator converted to period.")
                .build());

        // Tag 32B: Currency & Value Amount
        registry.put("32B", FieldMappingDefinition.builder()
                .tag("32B")
                .name("Currency & Value Amount")
                .defaultMxPath("CdtTrfTxInf/InstdAmt")
                .explanation("Maps instructed currency and amount to ISO 20022 Instructed Amount element with Ccy attribute.")
                .defaultWarning(null)
                .build());

        // Tag 50A/50F/50K: Ordering Customer
        registry.put("50K", FieldMappingDefinition.builder()
                .tag("50K")
                .name("Ordering Customer (Debtor - Option K Unstructured)")
                .defaultMxPath("CdtTrfTxInf/Dbtr & DbtrAcct")
                .explanation("Parses account line (prefixed with '/') into Debtor Account ID, and remaining lines into Debtor Name and Address elements.")
                .defaultWarning("Transformation from unstructured MT 50K text to structured ISO 20022 Debtor elements is lossy/ambiguous if address lines exceed 35/70 character length limits or contain merged address tokens.")
                .build());

        registry.put("50A", FieldMappingDefinition.builder()
                .tag("50A")
                .name("Ordering Customer (Debtor - Option A BIC)")
                .defaultMxPath("CdtTrfTxInf/Dbtr/FinInstnId/BICFI")
                .explanation("Maps Ordering Customer BIC code directly to ISO 20022 Debtor Financial Institution BICFI.")
                .defaultWarning(null)
                .build());

        registry.put("50F", FieldMappingDefinition.builder()
                .tag("50F")
                .name("Ordering Customer (Debtor - Option F Structured)")
                .defaultMxPath("CdtTrfTxInf/Dbtr")
                .explanation("Parses structured numbers/codes in 50F into ISO 20022 Debtor Name, Postal Address, and Party Identification elements.")
                .defaultWarning("Option F sub-field identifiers are converted into XML elements. Verify custom party identifiers for regional compliance.")
                .build());

        // Tag 52A/52D: Ordering Institution
        registry.put("52A", FieldMappingDefinition.builder()
                .tag("52A")
                .name("Ordering Institution (Debtor Agent - BIC)")
                .defaultMxPath("CdtTrfTxInf/DbtrAgt/FinInstnId/BICFI")
                .explanation("Maps Ordering Institution BIC to Debtor Agent BICFI in ISO 20022.")
                .defaultWarning(null)
                .build());

        // Tag 56A/56D: Intermediary Institution
        registry.put("56A", FieldMappingDefinition.builder()
                .tag("56A")
                .name("Intermediary Institution (Intermediary Agent 1)")
                .defaultMxPath("CdtTrfTxInf/IntrmyAgt1/FinInstnId/BICFI")
                .explanation("Maps Intermediary Institution BIC to Intermediary Agent 1 in ISO 20022.")
                .defaultWarning(null)
                .build());

        // Tag 57A/57D: Account With Institution
        registry.put("57A", FieldMappingDefinition.builder()
                .tag("57A")
                .name("Account With Institution (Creditor Agent - BIC)")
                .defaultMxPath("CdtTrfTxInf/CdtrAgt/FinInstnId/BICFI")
                .explanation("Maps Account With Institution BIC directly to Creditor Agent Financial Institution BICFI.")
                .defaultWarning(null)
                .build());

        registry.put("57D", FieldMappingDefinition.builder()
                .tag("57D")
                .name("Account With Institution (Creditor Agent - Name & Address)")
                .defaultMxPath("CdtTrfTxInf/CdtrAgt/FinInstnId/Nm")
                .explanation("Maps unstructured institution name and address to Creditor Agent Name and Address elements.")
                .defaultWarning("Unstructured institution lines converted to ISO 20022 name/address. Formatting loss may occur if lines exceed element constraints.")
                .build());

        // Tag 58A/58D: Beneficiary Institution
        registry.put("58A", FieldMappingDefinition.builder()
                .tag("58A")
                .name("Beneficiary Institution (Creditor Agent / Intermediary)")
                .defaultMxPath("CdtTrfTxInf/CdtrAgt/FinInstnId/BICFI")
                .explanation("Maps Beneficiary Institution BIC code in financial transfer messages (MT202/MT202COV) to Creditor Agent BICFI.")
                .defaultWarning(null)
                .build());

        // Tag 59/59A: Beneficiary Customer
        registry.put("59", FieldMappingDefinition.builder()
                .tag("59")
                .name("Beneficiary Customer (Creditor - Unstructured)")
                .defaultMxPath("CdtTrfTxInf/Cdtr & CdtrAcct")
                .explanation("Extracts account number (IBAN or proprietary) into Creditor Account ID, and remaining text lines into Creditor Name and Address elements.")
                .defaultWarning("MT 59 unstructured lines mapped to ISO Creditor elements. Address line wrapping or lack of structured postal details may be ambiguous for sanction screening.")
                .build());

        registry.put("59A", FieldMappingDefinition.builder()
                .tag("59A")
                .name("Beneficiary Customer (Creditor - BIC)")
                .defaultMxPath("CdtTrfTxInf/Cdtr/FinInstnId/BICFI")
                .explanation("Maps Beneficiary Customer BIC directly to ISO 20022 Creditor Identification.")
                .defaultWarning(null)
                .build());

        // Tag 60F/60M: Opening Balance
        registry.put("60F", FieldMappingDefinition.builder()
                .tag("60F")
                .name("Opening Balance (First / Final)")
                .defaultMxPath("Stmt/Bal/Amt & Stmt/Bal/Tp/CdOrPrtry")
                .explanation("Parses Debit/Credit mark (D/C), Date (YYMMDD), Currency, and Amount into ISO 20022 Statement Balance (OPBD type).")
                .defaultWarning("Date YY expanded to YYYY. D/C indicator mapped to ISO DebitCreditCode (CRDT/DBIT).")
                .build());

        // Tag 61: Statement Line
        registry.put("61", FieldMappingDefinition.builder()
                .tag("61")
                .name("Statement Line (Transaction Entry)")
                .defaultMxPath("Stmt/Ntry")
                .explanation("Parses Value Date, Entry Date, Debit/Credit Mark, Amount, Transaction Type Code, and Reference into ISO 20022 Entry details.")
                .defaultWarning("Complex sub-fields in MT Tag 61 (e.g., supplementary details //REF) parsed into entry items. Verify sub-code interpretation.")
                .build());

        // Tag 62F/62M: Closing Balance
        registry.put("62F", FieldMappingDefinition.builder()
                .tag("62F")
                .name("Closing Balance (Booked)")
                .defaultMxPath("Stmt/Bal (CLBD)")
                .explanation("Parses Debit/Credit mark, Date, Currency, and Amount into ISO 20022 Closing Booked Balance (CLBD).")
                .defaultWarning(null)
                .build());

        // Tag 70: Remittance Information
        registry.put("70", FieldMappingDefinition.builder()
                .tag("70")
                .name("Remittance Information")
                .defaultMxPath("CdtTrfTxInf/RmtInf/Ustrd")
                .explanation("Maps free-text remittance details to ISO 20022 Unstructured Remittance Information (Ustrd).")
                .defaultWarning("Free-text line breaks converted to space-separated text. Total length capped at 140 characters per ISO 20022 Ustrd element limit.")
                .build());

        // Tag 71A: Details of Charges
        registry.put("71A", FieldMappingDefinition.builder()
                .tag("71A")
                .name("Details of Charges")
                .defaultMxPath("CdtTrfTxInf/ChrgBr")
                .explanation("Translates MT charge codes (BEN, OUR, SHA) to ISO 20022 Charge Bearer enumeration (CRED, DEBT, SHAR).")
                .defaultWarning(null)
                .build());

        // Tag 72: Sender to Receiver Information
        registry.put("72", FieldMappingDefinition.builder()
                .tag("72")
                .name("Sender to Receiver Information")
                .defaultMxPath("CdtTrfTxInf/InstrForNxtAgt & PrvtNts")
                .explanation("Parses structured codes (/ACC/, /REC/, /INS/) into ISO 20022 Instruction For Next Agent or Private Notes.")
                .defaultWarning("Free-text notes in Tag 72 are mapped to unstructured instructions or proprietary elements; unformatted lines may require manual review.")
                .build());

        // Tag 121: UETR (Block 3)
        registry.put("121", FieldMappingDefinition.builder()
                .tag("121")
                .name("Unique End-to-End Transaction Reference (UETR)")
                .defaultMxPath("PmtId/UETR")
                .explanation("Maps SWIFT Block 3 {121:...} UUID directly to ISO 20022 UETR element.")
                .defaultWarning(null)
                .build());
    }

    public FieldMappingDefinition getDefinition(String tag) {
        if (tag == null) return null;
        String cleanTag = tag.replaceAll("^:?", "").replaceAll(":?$", "").trim();
        return registry.get(cleanTag);
    }

    public Map<String, FieldMappingDefinition> getAllDefinitions() {
        return Collections.unmodifiableMap(registry);
    }
}
