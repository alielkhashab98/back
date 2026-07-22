package com.mtsaas.backend.api;

import com.mtsaas.backend.infrastructure.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class HealthController {

    @Lazy
    private final UserRepository userRepository;

    @GetMapping
    @SuppressWarnings("unused")
    public ResponseEntity<Map<String, String>> health() {
        try {
            // Wake up database by executing a simple count query
            userRepository.count();
            return ResponseEntity.ok(Map.of("status", "UP", "database", "connected"));
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() :
                e.getClass().getSimpleName();
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "error",
                errorMessage));
        }
    }
}