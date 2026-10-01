package com.mtsaas.backend.domain.swift.mapping;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMappingResult {
    private String sourceField;      // e.g. ":32A:"
    private String sourceFieldName;  // e.g. "Value Date, Currency & Interbank Settled Amount"
    private String sourceType;       // e.g. "Option A (Date/Ccy/Amt)", "Header (BIC)", "Option K (Unstructured)"
    private String sourceValue;      // Raw MT or MX value
    private String targetElement;    // Mapped ISO 20022 XML path
    private String targetValue;      // Formatted summary target value
    private List<String> targetSubValues; // Clean list of individual element sub-values (no raw '/' separators)
    private String explanation;      // Reusable mapping rule explanation
    private String warning;          // Lossy or ambiguous warning (null if none)
}
