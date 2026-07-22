package com.mtsaas.backend.api;

import com.mtsaas.backend.application.service.ConversionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversion")
@RequiredArgsConstructor
public class ConversionController {

    private final ConversionService conversionService;

    @PostMapping("/mt-to-mx")
    public ResponseEntity<Map<String, String>> convertMtToMx(@RequestBody Map<String, String> request) {
        try {
            String mtContent = request.get("mtMessage");
            if (mtContent == null || mtContent.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "MT message is required"));
            }

            String messageType = request.get("messageType");

            String mxXml = conversionService.convertMtToMx(mtContent, messageType);
            return ResponseEntity.ok(Map.of("xml", mxXml));
        } catch (RuntimeException e) {
            if ("ANONYMOUS_LIMIT_REACHED".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Guest trial limit reached. Sign up to get 10 free credits.", "code",
                                "ANONYMOUS_LIMIT_REACHED"));
            }
            if ("INSUFFICIENT_CREDITS".equals(e.getMessage())
                    || (e.getMessage() != null && e.getMessage().contains("Insufficient credits"))) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(Map.of("error", "Insufficient credits. Please purchase more to continue.", "code",
                                "INSUFFICIENT_CREDITS"));
            }
            if ("LOG_SAVE_FAILED".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "System error: Unable to record conversion. Please try again later.",
                                "code", "LOG_SAVE_FAILED"));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Conversion failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error during conversion"));
        }
    }

    @PostMapping("/mx-to-mt")
    public ResponseEntity<Map<String, String>> convertMxToMt(@RequestBody Map<String, String> request) {
        try {
            String mxContent = request.get("mxMessage");
            if (mxContent == null || mxContent.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "MX message is required"));
            }

            String messageType = request.get("messageType");

            String mtContent = conversionService.convertMxToMt(mxContent, messageType);
            return ResponseEntity.ok(Map.of("mt", mtContent));
        } catch (RuntimeException e) {
            if ("ANONYMOUS_LIMIT_REACHED".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Guest trial limit reached. Sign up to get 10 free credits.", "code",
                                "ANONYMOUS_LIMIT_REACHED"));
            }
            if ("INSUFFICIENT_CREDITS".equals(e.getMessage())
                    || (e.getMessage() != null && e.getMessage().contains("Insufficient credits"))) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(Map.of("error", "Insufficient credits. Please purchase more to continue.", "code",
                                "INSUFFICIENT_CREDITS"));
            }
            if ("LOG_SAVE_FAILED".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "System error: Unable to record conversion. Please try again later.",
                                "code", "LOG_SAVE_FAILED"));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Conversion failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error during conversion"));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateMtMessage(@RequestBody Map<String, String> request) {
        String mtContent = request.get("mtMessage");
        if (mtContent == null || mtContent.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "errors", java.util.List.of("MT message content is required")));
        }
        String messageType = request.get("messageType");
        Map<String, Object> validationResult = conversionService.validateMtMessage(mtContent, messageType);
        boolean valid = Boolean.TRUE.equals(validationResult.get("valid"));
        if (valid) {
            return ResponseEntity.ok(validationResult);
        }
        return ResponseEntity.unprocessableEntity().body(validationResult);
    }
}
