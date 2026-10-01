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
                    .sourceType("SWIFT Block 1 Header BIC")
                    .sourceValue(mtMessage.getSender())
                    .targetElement("AppHdr/Fr/FIId/FinInstnId/BICFI")
                    .targetValue(mtMessage.getSender())
                    .targetSubValues(List.of("BICFI: " + mtMessage.getSender()))
                    .explanation("Maps Block 1 Header Sender BIC to ISO 20022 Application Header From (Fr) BICFI.")
                    .warning(null)
                    .build());
        }

        if (mtMessage.getReceiver() != null && !mtMessage.getReceiver().isEmpty()) {
            results.add(FieldMappingResult.builder()
                    .sourceField("Block 2")
                    .sourceFieldName("Receiver BIC")
                    .sourceType("SWIFT Block 2 Header BIC")
                    .sourceValue(mtMessage.getReceiver())
                    .targetElement("AppHdr/To/FIId/FinInstnId/BICFI")
                    .targetValue(mtMessage.getReceiver())
                    .targetSubValues(List.of("BICFI: " + mtMessage.getReceiver()))
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
            String sourceType = (def != null && def.getSourceType() != null) ? def.getSourceType() : "MT Field Format";
            String targetElement = (def != null) ? def.getDefaultMxPath() : "Document/...";
            String explanation = (def != null) ? def.getExplanation() : "MT Tag :" + tag + ": mapped to corresponding ISO 20022 XML element.";
            String warning = (def != null) ? def.getDefaultWarning() : null;

            // Extract target sub-values cleanly without joining with '/'
            List<String> targetSubValues = extractTargetSubValues(tag, value);
            String formattedTargetValue = String.join(" • ", targetSubValues);
            warning = evaluateDynamicWarning(tag, value, warning);

            results.add(FieldMappingResult.builder()
                    .sourceField(":" + tag + ":")
                    .sourceFieldName(fieldName)
                    .sourceType(sourceType)
                    .sourceValue(value)
                    .targetElement(targetElement)
                    .targetValue(formattedTargetValue)
                    .targetSubValues(targetSubValues)
                    .explanation(explanation)
                    .warning(warning)
                    .build());
        }

        return results;
    }

    public List<FieldMappingResult> generateMappingsForMxToMt(String mxXml, String mtContent) {
        List<FieldMappingResult> results = new ArrayList<>();

        if (mxXml == null || mxXml.isEmpty()) {
            return results;
        }

        // Standard MX to MT mappings
        results.add(FieldMappingResult.builder()
                .sourceField("AppHdr/BizMsgIdr")
                .sourceFieldName("Business Message Identifier")
                .sourceType("ISO Header String")
                .sourceValue(extractXmlNodeValue(mxXml, "BizMsgIdr"))
                .targetElement(":20:")
                .targetValue(extractMtTagValue(mtContent, "20"))
                .targetSubValues(List.of("Reference: " + extractMtTagValue(mtContent, "20")))
                .explanation("ISO 20022 Business Message Identifier mapped to MT Tag 20 Sender's Reference.")
                .warning(null)
                .build());

        results.add(FieldMappingResult.builder()
                .sourceField("IntrBkSttlmAmt")
                .sourceFieldName("Interbank Settlement Amount & Date")
                .sourceType("ISO Amount & Date Element")
                .sourceValue(extractXmlNodeValue(mxXml, "IntrBkSttlmAmt"))
                .targetElement(":32A:")
                .targetValue(extractMtTagValue(mtContent, "32A"))
                .targetSubValues(List.of("Tag 32A Composite: " + extractMtTagValue(mtContent, "32A")))
                .explanation("ISO 20022 Settlement Amount and Date collapsed into MT Tag 32A YYMMDD format.")
                .warning("ISO 4-digit YYYY date converted to MT 2-digit YY date format. Verify century calculation.")
                .build());

        results.add(FieldMappingResult.builder()
                .sourceField("Dbtr")
                .sourceFieldName("Debtor (Ordering Customer)")
                .sourceType("ISO Debtor Complex Element")
                .sourceValue(extractXmlNodeValue(mxXml, "Dbtr"))
                .targetElement(":50K:")
                .targetValue(extractMtTagValue(mtContent, "50K"))
                .targetSubValues(List.of("Tag 50K Unstructured Lines: " + extractMtTagValue(mtContent, "50K")))
                .explanation("ISO 20022 structured Debtor Name and Address merged into MT 50K unstructured 35-character lines.")
                .warning("Lossy transformation: Structured ISO postal address merged into unstructured text lines; details exceeding line limits truncated.")
                .build());

        return results;
    }

    private List<String> extractTargetSubValues(String tag, String value) {
        List<String> subValues = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return subValues;
        }

        if ("32A".equals(tag) && value.length() >= 9) {
            try {
                String date = "20" + value.substring(0, 2) + "-" + value.substring(2, 4) + "-" + value.substring(4, 6);
                String ccy = value.substring(6, 9);
                String amtStr = value.substring(9).replace(",", ".");
                subValues.add("Settlement Amount: " + amtStr);
                subValues.add("Currency: " + ccy);
                subValues.add("Settlement Date: " + date);
                return subValues;
            } catch (Exception e) {
                subValues.add("Value: " + value);
                return subValues;
            }
        }

        if ("71A".equals(tag)) {
            String code = value.trim();
            if ("SHA".equalsIgnoreCase(code)) subValues.add("Charge Bearer: SHAR (Shared)");
            else if ("OUR".equalsIgnoreCase(code)) subValues.add("Charge Bearer: DEBT (Borne by Debtor)");
            else if ("BEN".equalsIgnoreCase(code)) subValues.add("Charge Bearer: CRED (Borne by Creditor)");
            else subValues.add("Charge Bearer Code: " + code);
            return subValues;
        }

        // For multi-line fields like 50K, 59, 70, split by line break into distinct sub-items
        if (value.contains("\n")) {
            String[] lines = value.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    if (i == 0 && line.startsWith("/")) {
                        subValues.add("Account / Identification: " + line);
                    } else {
                        subValues.add("Line " + (i + 1) + ": " + line);
                    }
                }
            }
            return subValues;
        }

        subValues.add(value);
        return subValues;
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
        return tag + " (ISO element)";
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
