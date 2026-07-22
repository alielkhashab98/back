package com.mtsaas.backend.api;

import com.mtsaas.backend.application.service.ConversionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversionControllerTest {

    @Mock
    private ConversionService conversionService;

    @InjectMocks
    private ConversionController controller;

    @Test
    void validateMtMessageReturns422ForInvalidValidationResult() {
        when(conversionService.validateMtMessage("bad", "MT103"))
                .thenReturn(Map.of("valid", false, "errors", java.util.List.of("Missing mandatory field :32A: Amount/Currency.")));

        ResponseEntity<Map<String, Object>> response = controller.validateMtMessage(Map.of(
                "mtMessage", "bad",
                "messageType", "MT103"
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertTrue(response.getBody().containsKey("errors"));
    }

    @Test
    void validateMtMessageReturns200ForValidValidationResult() {
        when(conversionService.validateMtMessage("good", "MT103"))
                .thenReturn(Map.of("valid", true, "errors", java.util.List.of()));

        ResponseEntity<Map<String, Object>> response = controller.validateMtMessage(Map.of(
                "mtMessage", "good",
                "messageType", "MT103"
        ));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
