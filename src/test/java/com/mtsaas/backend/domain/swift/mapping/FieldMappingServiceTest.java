package com.mtsaas.backend.domain.swift.mapping;

import com.mtsaas.backend.domain.swift.mt.MtParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldMappingServiceTest {

    private FieldMappingService mappingService;
    private MtParser mtParser;

    @BeforeEach
    void setUp() {
        FieldMappingRegistry registry = new FieldMappingRegistry();
        mappingService = new FieldMappingService(registry);
        mtParser = new MtParser();
    }

    @Test
    void testMt103FieldMappingGeneration() {
        String mt103 = "{1:F01BANKDEFFAXXX0000000000}{2:I103BANKBEBBAXXXN}{4:\n" +
                ":20:TRANSREF123\n" +
                ":23B:CRED\n" +
                ":32A:260518EUR125000,50\n" +
                ":50K:/12345678\nJOHN DOE\n123 MAPLE STREET\n" +
                ":59:/87654321\nJANE SMITH\n" +
                ":70:REMITTANCE INFO FOR INVOICE 9982\n" +
                ":71A:SHA\n-}";

        var mtMessage = mtParser.parse(mt103);
        List<FieldMappingResult> results = mappingService.generateMappingsForMtToMx(mtMessage, "<xml></xml>");

        assertNotNull(results);
        assertFalse(results.isEmpty());

        // Check Tag 20 mapping
        FieldMappingResult tag20 = results.stream()
                .filter(r -> ":20:".equals(r.getSourceField()))
        .findFirst().orElse(null);
        assertNotNull(tag20);
        assertEquals("TRANSREF123", tag20.getSourceValue());
        assertEquals("PmtId/InstrId & GrpHdr/MsgId", tag20.getTargetElement());
        assertNull(tag20.getWarning());

        // Check Tag 32A mapping
        FieldMappingResult tag32A = results.stream()
                .filter(r -> ":32A:".equals(r.getSourceField()))
                .findFirst().orElse(null);
        assertNotNull(tag32A);
        assertTrue(tag32A.getTargetValue().contains("125000.50"));
        assertTrue(tag32A.getTargetValue().contains("EUR"));
        assertNotNull(tag32A.getWarning());

        // Check Tag 71A mapping
        FieldMappingResult tag71A = results.stream()
                .filter(r -> ":71A:".equals(r.getSourceField()))
                .findFirst().orElse(null);
        assertNotNull(tag71A);
        assertEquals("SHAR", tag71A.getTargetValue());
    }

    @Test
    void testLossyWarningDetection() {
        String longRemittance = "A".repeat(150);
        String mt103 = "{1:F01BANKDEFFAXXX0000000000}{2:I103BANKBEBBAXXXN}{4:\n" +
                ":20:TRANSREF123\n" +
                ":70:" + longRemittance + "\n-}";

        var mtMessage = mtParser.parse(mt103);
        List<FieldMappingResult> results = mappingService.generateMappingsForMtToMx(mtMessage, "<xml></xml>");

        FieldMappingResult tag70 = results.stream()
                .filter(r -> ":70:".equals(r.getSourceField()))
                .findFirst().orElse(null);

        assertNotNull(tag70);
        assertNotNull(tag70.getWarning());
        assertTrue(tag70.getWarning().contains("exceeds 140-character limit"));
    }
}
