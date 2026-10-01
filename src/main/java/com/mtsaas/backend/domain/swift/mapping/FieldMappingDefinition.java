package com.mtsaas.backend.domain.swift.mapping;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMappingDefinition {
    private String tag;             // Tag identifier (e.g., "20", "32A", "50K")
    private String name;            // Human readable field name
    private String defaultMxPath;   // Target ISO element path
    private String explanation;     // Reusable transformation explanation
    private String defaultWarning;  // Lossy/ambiguous warning if applicable
}
