package com.mtsaas.backend.domain.swift.mapping;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FieldMappingService {

    private final FieldMappingRegistry registry;

    public List<FieldMappingResult> generateMappingsForMtToMx(MtMessage mtMessage, String mxXml) {
        List<FieldMappingResult> results = new ArrayList<>();
        if (mtMessage == null || mtMessage.getTags() == null) {
            return results;
        }

        Map<String, String> tags = mtMessage.getTags();

        // 1. Process Sender/Receiver Block Headers if available
        if (mtMessage.getSender() != null && !mtMessage.getSender().isEmpty()) {
            results.add(FieldMappingResult.builder()
                    .sourceField("Block 1")
                    .sourceFieldName("Sender BIC")
                    .sourceValue(mtMessage.getSender())
                    .targetElement("AppHdr/Fr/FIId/FinInstnId/BICFI")
                    .targetValue(mtMessage.getSender())
                    .explanation("Maps Block 1 Header Sender BIC to ISO 20022 Application Header From (Fr) BICFI.")
                    .warning(null)
                    .build());
        }

        if (mtMessage.getReceiver() != null && !mtMessage.getReceiver().isEmpty()) {
            results.add(FieldMappingResult.builder()
                    .sourceField("Block 2")
                    .sourceFieldName("Receiver BIC")
                    .sourceValue(mtMessage.getReceiver())
                    .targetElement("AppHdr/To/FIId/FinInstnId/BICFI")
                    .targetValue(mtMessage.getReceiver())
                    .explanation("Maps Block 2 Header Receiver BIC to ISO 20022 Application Header To BICFI.")
                    .warning(null)
                    .build());
        }

        // 2. Process Block 4 Tag Mappings
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String tag = entry.getKey();
            String value = entry.getValue();

            FieldMappingDefinition def = registry.getDefinition(tag);

            String fieldName = (def != null) ? def.getName() : "Tag :" + tag + ":";
            String targetElement = (def != null) ? def.getDefaultMxPath() : "Document/...";
            String explanation = (def != null) ? def.getExplanation() : "MT Tag :" + tag + ": mapped to corresponding ISO 20022 XML element.";
            String warning = (def != null) ? def.getDefaultWarning() : null;

            // Evaluate specific dynamic target values & refined warnings
            String formattedTargetValue = formatTargetValue(tag, value);
            warning = evaluateDynamicWarning(tag, value, warning);

            results.add(FieldMappingResult.builder()
                    .sourceField(":" + tag + ":")
                    .sourceFieldName(fieldName)
                    .sourceValue(value)
                    .targetElement(targetElement)
                    .targetValue(formattedTargetValue)
                    .explanation(explanation)
                    .warning(warning)
                    .build());
        }

        return results;
    }

    public List<FieldMappingResult> generateMappingsForMxToMt(String mxXml, String mtContent) {
        List<FieldMappingResult> results = new ArrayList<>();

        // Generate MX to MT mapping view items based on parsed elements
        if (mxXml == null || mxXml.isEmpty()) {
            return results;
        }

        // Standard MX to MT mappings
        results.add(FieldMappingResult.builder()
                .sourceField("AppHdr/BizMsgIdr")
                .sourceFieldName("Business Message Identifier")
                .sourceValue(extractXmlNodeValue(mxXml, "BizMsgIdr"))
                .targetElement(":20:")
                .targetValue(extractMtTagValue(mtContent, "20"))
                .explanation("ISO 20022 Business Message Identifier mapped to MT Tag 20 Sender's Reference.")
                .warning(null)
                .build());

        results.add(FieldMappingResult.builder()
                .sourceField("IntrBkSttlmAmt")
                .sourceFieldName("Interbank Settlement Amount & Date")
                .sourceValue(extractXmlNodeValue(mxXml, "IntrBkSttlmAmt"))
                .targetElement(":32A:")
                .targetValue(extractMtTagValue(mtContent, "32A"))
                .explanation("ISO 20022 Settlement Amount and Date collapsed into MT Tag 32A YYMMDD format.")
                .warning("ISO 4-digit YYYY date converted to MT 2-digit YY date format. Verify century calculation.")
                .build());

        results.add(FieldMappingResult.builder()
                .sourceField("Dbtr")
                .sourceFieldName("Debtor (Ordering Customer)")
                .sourceValue(extractXmlNodeValue(mxXml, "Dbtr"))
                .targetElement(":50K:")
                .targetValue(extractMtTagValue(mtContent, "50K"))
                .explanation("ISO 20022 structured Debtor Name and Address merged into MT 50K unstructured 35-character lines.")
                .warning("Lossy transformation: Structured ISO postal address merged into unstructured text lines; details exceeding line limits truncated.")
                .build());

        results.add(FieldMappingResult.builder()
                .sourceField("Cdtr")
                .sourceFieldName("Creditor (Beneficiary Customer)")
                .sourceValue(extractXmlNodeValue(mxXml, "Cdtr"))
                .targetElement(":59:")
                .targetValue(extractMtTagValue(mtContent, "59"))
                .explanation("ISO 20022 structured Creditor Name and Account collapsed into MT Tag 59 lines.")
                .warning("Lossy transformation: ISO structured Creditor address collapsed into unstructured MT lines.")
                .build());

        results.add(FieldMappingResult.builder()
                .sourceField("ChrgBr")
                .sourceFieldName("Charge Bearer Code")
                .sourceValue(extractXmlNodeValue(mxXml, "ChrgBr"))
                .targetElement(":71A:")
                .targetValue(extractMtTagValue(mtContent, "71A"))
                .explanation("ISO 20022 Charge Bearer code mapped back to MT 71A code (DEBT->OUR, CRED->BEN, SHAR->SHA).")
                .warning(null)
                .build());

        return results;
    }

    private String formatTargetValue(String tag, String value) {
        if (value == null) return "";
        if ("32A".equals(tag)) {
            // e.g. 260518EUR125000,50 -> 125000.50 (Ccy: EUR, Date: 2026-05-18)
            if (value.length() >= 9) {
                try {
                    String date = "20" + value.substring(0, 2) + "-" + value.substring(2, 4) + "-" + value.substring(4, 6);
                    String ccy = value.substring(6, 9);
                    String amtStr = value.substring(9).replace(",", ".");
                    return amtStr + " (Ccy=" + ccy + ", Date=" + date + ")";
                } catch (Exception e) {
                    return value;
                }
            }
        }
        if ("71A".equals(tag)) {
            if ("SHA".equalsIgnoreCase(value)) return "SHAR";
            if ("OUR".equalsIgnoreCase(value)) return "DEBT";
            if ("BEN".equalsIgnoreCase(value)) return "CRED";
        }
        return value.replace("\n", " / ");
    }

    private String evaluateDynamicWarning(String tag, String value, String defaultWarning) {
        if ("70".equals(tag) && value != null && value.length() > 140) {
            return "Ambiguous / Lossy: Remittance text length (" + value.length() + " chars) exceeds 140-character limit for ISO 20022 Ustrd. Excess text truncated.";
        }
        if (("50K".equals(tag) || "59".equals(tag)) && value != null && value.contains("\n")) {
            String[] lines = value.split("\n");
            for (String line : lines) {
                if (line.length() > 35) {
                    return "Lossy transformation warning: Line in field :" + tag + ": exceeds standard 35 characters (" + line.length() + " chars). Truncated during ISO element mapping.";
                }
            }
        }
        return defaultWarning;
    }

    private String extractXmlNodeValue(String xml, String tag) {
        if (xml == null) return "";
        int start = xml.indexOf("<" + tag);
        if (start != -1) {
            int contentStart = xml.indexOf(">", start) + 1;
            int end = xml.indexOf("</" + tag + ">", contentStart);
            if (end != -1) {
                return xml.substring(contentStart, end).trim();
            }
        }
        return tag + " (Value extracted from ISO message)";
    }

    private String extractMtTagValue(String mt, String tag) {
        if (mt == null) return "";
        int idx = mt.indexOf(":" + tag + ":");
        if (idx != -1) {
            int start = idx + tag.length() + 2;
            int end = mt.indexOf("\n:", start);
            if (end == -1) end = mt.indexOf("\n-", start);
            if (end == -1) end = mt.length();
            return mt.substring(start, end).trim();
        }
        return "Mapped tag :" + tag + ":";
    }
}
