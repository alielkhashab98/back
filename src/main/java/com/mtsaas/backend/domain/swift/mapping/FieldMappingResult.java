package com.mtsaas.backend.domain.swift.mapping;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMappingResult {
    private String sourceField;      // e.g. "20", "32A", "50K", "59"
    private String sourceFieldName;  // e.g. "Sender's Reference"
    private String sourceValue;      // Raw MT or MX value
    private String targetElement;    // Mapped ISO 20022 XML path or MT Tag
    private String targetValue;      // Value in target message
    private String explanation;      // Reusable mapping rule explanation
    private String warning;          // Lossy or ambiguous warning (null if none)
}
