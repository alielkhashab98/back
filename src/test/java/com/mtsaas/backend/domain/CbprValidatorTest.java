package com.mtsaas.backend.domain;

import com.mtsaas.backend.domain.swift.mt.MtMessage;
import com.mtsaas.backend.domain.swift.mt.MtParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CbprValidatorTest {

    private final MtParser parser = new MtParser();

    @Test
    void malformedBlock1HeaderIsRejected() {
        MtMessage message = new MtMessage();
        try {
            parser.parse("{1:F01BANKBEBBAXXX}");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("Syntax error in SWIFT Block 1"));
            return;
        }
        throw new AssertionError("Expected malformed Block 1 header to be rejected");
    }

    @Test
    void missing32ATagValueProducesValidationError() {
        MtMessage message = new MtMessage();
        message.setTags(Map.of("20", "REF001"));
        message.setSender("BANKDEFFXXX");
        message.setReceiver("BANKBEBBXXX");

        var errors = CbprValidator.validatePacs008(message);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains(":32A:")));
    }

    @Test
    void invalidDateIn32AProducesValidationError() {
        MtMessage message = new MtMessage();
        message.setTags(Map.of("20", "REF001", "32A", "260231USD1000,00"));
        message.setSender("BANKDEFFXXX");
        message.setReceiver("BANKBEBBXXX");

        var errors = CbprValidator.validatePacs008(message);
        assertTrue(errors.stream().anyMatch(error -> error.contains("Invalid value date")));
    }
}
