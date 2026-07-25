package com.mtsaas.backend.application.service;

import com.mtsaas.backend.domain.Conversion;
import com.mtsaas.backend.domain.User;
import com.mtsaas.backend.domain.swift.mt.MtGenerator;
import com.mtsaas.backend.domain.swift.mt.MtParser;
import com.mtsaas.backend.domain.swift.mx.MxGenerator;
import com.mtsaas.backend.domain.swift.mx.MxParser;
import com.mtsaas.backend.infrastructure.repository.ConversionRepository;
import com.mtsaas.backend.infrastructure.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

@Service
//@Lazy
@RequiredArgsConstructor
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    private final MtParser mtParser;
    private final MxParser mxParser;
    private final List<MxGenerator> mxGenerators;
    private final List<MtGenerator> mtGenerators;
    private final ConversionRepository conversionRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;

    private String getClientIp() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                    .getRequest();
            String ipAddress = request.getHeader("X-Forwarded-For");
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getHeader("Proxy-Client-IP");
            }
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getHeader("WL-Proxy-Client-IP");
            }
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getRemoteAddr();
            }
            return ipAddress;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public String convertMtToMx(String mtContent, String messageType) {
        User user = null;
        Conversion conversion = new Conversion();
        String ipAddress = getClientIp();

        try {
            // 1. Get Current User and IP
            log.info("Starting conversion for IP: {}", ipAddress);
            try {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    String name = auth.getName();
                    log.debug("Auth found: {}, name: {}", auth.getClass().getSimpleName(), name);
                    if (name != null && !"anonymousUser".equalsIgnoreCase(name)) {
                        user = userRepository.findByEmail(name).orElse(null);
                    }
                } else {
                    log.debug("No authentication or not authenticated");
                }
            } catch (Exception e) {
                log.warn("Error getting authentication: {}", e.getMessage());
            }

            log.info("User identified: {}", user != null ? user.getEmail() : "ANONYMOUS");

            // 2. Set up initial conversion log fields (CRITICAL for failure logging)
            conversion.setUser(user);
            conversion.setConversionType("MT_TO_MX");
            conversion.setInputContent(mtContent);
            conversion.setIpAddress(ipAddress);
            conversion.setStatus(Conversion.Status.SUCCESS); // Default to SUCCESS, updated to FAILED in catch

            // 3. Performance Credit Check
            if (user == null) {
                // Anonymous user check
                long anonymousCount = conversionRepository.countByIpAddressAndUserIsNull(ipAddress);
                log.info("Anonymous conversion count for IP {}: {}", ipAddress, anonymousCount);
                if (anonymousCount >= TrialLimitPolicy.GUEST_FREE_CONVERSIONS) {
                    log.warn("Anonymous limit reached for IP: {}", ipAddress);
                    throw new RuntimeException("ANONYMOUS_LIMIT_REACHED");
                }
            } else {
                // Authenticated user check - use CreditService to get actual available credits
                long availableCredits = creditService.getUserCreditBalance(user.getEmail()).getAvailableCredits();
                log.info("User {} has {} available credits", user.getEmail(), availableCredits);
                if (availableCredits <= 0) {
                    log.warn("Insufficient credits for conversion - user {} has 0 credits", user.getEmail());
                    throw new RuntimeException("INSUFFICIENT_CREDITS");
                }
            }

            // 4. Parse MT
            var mtMessage = mtParser.parse(mtContent);

            // Override type if provided and valid
            if (messageType != null && !messageType.isBlank()) {
                // Remove "MT" prefix if present
                String typeCode = messageType.toUpperCase().startsWith("MT") ? messageType.substring(2) : messageType;
                mtMessage.setType(typeCode);
            }

            // 5. Find Generator
            var generator = mxGenerators.stream()
                    .filter(g -> g.supports(mtMessage.getType()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Unsupported MT type: " + mtMessage.getType()));

            // 6. Generate MX
            String mxXml = generator.generate(mtMessage);
            conversion.setOutputContent(mxXml);

            // 7. Save Conversion Log
            saveConversionLog(conversion);

            // 8. Deduct Credit Only for Authenticated Users
            if (user != null) {
                creditService.recordCreditUsage(user, 1L, "MT_TO_MX",
                        "Converted MT message of type " + mtMessage.getType(), conversion);
            }

            return mxXml;

        } catch (Exception e) {
            conversion.setStatus(Conversion.Status.FAILED);
            conversion.setErrorMessage(e.getMessage());
            saveConversionLog(conversion);
            throw e;
        }
    }

    @Transactional
    public void saveConversionLog(Conversion conversion) {
        if (conversion == null) {
            log.warn("Attempted to save null conversion log");
            return;
        }
        try {
            conversionRepository.save(conversion);
            log.info("Saved conversion log with ID: {} for user: {}",
                    conversion.getId(), extractionUserEmail(conversion.getUser()));
        } catch (Exception e) {
            log.error("CRITICAL: Failed to save conversion log: {}. This will bypass limits!", e.getMessage(), e);
            throw new RuntimeException("LOG_SAVE_FAILED", e);
        }
    }

    private String extractionUserEmail(User user) {
        return user != null ? user.getEmail() : "ANONYMOUS";
    }

    /**
     * Convert MX (ISO 20022) message to MT (SWIFT FIN) format.
     */
    public String convertMxToMt(String mxContent, String messageType) {
        User user = null;
        Conversion conversion = new Conversion();
        String ipAddress = getClientIp();

        try {
            // 1. Get Current User and IP
            log.info("Starting MX to MT conversion for IP: {}", ipAddress);
            try {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    String name = auth.getName();
                    if (name != null && !"anonymousUser".equalsIgnoreCase(name)) {
                        user = userRepository.findByEmail(name).orElse(null);
                    }
                }
            } catch (Exception e) {
                log.warn("Error getting authentication: {}", e.getMessage());
            }

            log.info("User identified: {}", user != null ? user.getEmail() : "ANONYMOUS");

            // 2. Set up conversion log
            conversion.setUser(user);
            conversion.setConversionType("MX_TO_MT");
            conversion.setInputContent(mxContent);
            conversion.setIpAddress(ipAddress);
            conversion.setStatus(Conversion.Status.SUCCESS);

            // 3. Credit Check
            if (user == null) {
                long anonymousCount = conversionRepository.countByIpAddressAndUserIsNull(ipAddress);
                log.info("Anonymous conversion count for IP {}: {}", ipAddress, anonymousCount);
                if (anonymousCount >= TrialLimitPolicy.GUEST_FREE_CONVERSIONS) {
                    log.warn("Anonymous limit reached for IP: {}", ipAddress);
                    throw new RuntimeException("ANONYMOUS_LIMIT_REACHED");
                }
            } else {
                // Authenticated user check - use CreditService to get actual available credits
                long availableCredits = creditService.getUserCreditBalance(user.getEmail()).getAvailableCredits();
                log.info("User {} has {} available credits", user.getEmail(), availableCredits);
                if (availableCredits <= 0) {
                    log.warn("Insufficient credits for conversion - user {} has 0 credits", user.getEmail());
                    throw new RuntimeException("INSUFFICIENT_CREDITS");
                }
            }

            // 4. Parse MX
            var mxMessage = mxParser.parse(mxContent);

            // Determine message type if not provided
            final String mxType;
            if (messageType != null && !messageType.isBlank()) {
                mxType = messageType;
            } else if (mxMessage.getMessageType() != null && !mxMessage.getMessageType().isBlank()) {
                mxType = mxMessage.getMessageType();
            } else {
                throw new RuntimeException("Could not determine MX message type");
            }

            // 5. Find Generator
            var generator = mtGenerators.stream()
                    .filter(g -> g.supports(mxType))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Unsupported MX type: " + mxType));

            // 6. Generate MT
            String mtContent = generator.generate(mxMessage);
            conversion.setOutputContent(mtContent);

            // 7. Save Conversion Log
            saveConversionLog(conversion);

            // 8. Deduct Credit
            if (user != null) {
                creditService.recordCreditUsage(user, 1L, "MX_TO_MT",
                        "Converted MX message of type " + mxType, conversion);
            }

            return mtContent;

        } catch (Exception e) {
            conversion.setStatus(Conversion.Status.FAILED);
            conversion.setErrorMessage(e.getMessage());
            saveConversionLog(conversion);
            throw e;
        }
    }
    public java.util.Map<String, Object> validateMtMessage(String mtContent, String messageType) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            var mtMessage = mtParser.parse(mtContent);
            if (messageType != null && !messageType.isBlank()) {
                String typeCode = messageType.toUpperCase().startsWith("MT") ? messageType.substring(2) : messageType;
                mtMessage.setType(typeCode);
            }

            String targetType = mtMessage.getType() != null ? mtMessage.getType() : "103";
            List<String> errors;

            switch (targetType) {
                case "101":
                    errors = com.mtsaas.backend.domain.CbprValidator.validatePain001(mtMessage);
                    break;
                case "202COV":
                case "202_COV":
                    errors = com.mtsaas.backend.domain.CbprValidator.validatePacs009Cov(mtMessage);
                    break;
                case "202":
                    errors = com.mtsaas.backend.domain.CbprValidator.validatePacs009(mtMessage);
                    break;
                case "102":
                    errors = com.mtsaas.backend.domain.CbprValidator.validatePacs008Bulk(mtMessage);
                    break;
                case "103":
                default:
                    errors = com.mtsaas.backend.domain.CbprValidator.validatePacs008(mtMessage);
                    break;
            }

            result.put("valid", errors.isEmpty());
            result.put("messageType", "MT" + targetType);
            result.put("errors", errors);
            result.put("detectedTags", mtMessage.getTags() != null ? mtMessage.getTags() : java.util.Collections.emptyMap());
            return result;
        } catch (Exception e) {
            result.put("valid", false);
            result.put("messageType", messageType != null ? messageType : "UNKNOWN");
            result.put("errors", java.util.List.of("Syntax error parsing SWIFT FIN structure: " + e.getMessage()));
            result.put("detectedTags", java.util.Collections.emptyMap());
            return result;
        }
    }
}
