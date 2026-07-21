package com.mtsaas.backend.application.service;

import com.mtsaas.backend.application.dto.AuthDto;
import com.mtsaas.backend.domain.EmailVerificationToken;
import com.mtsaas.backend.domain.Role;

import com.mtsaas.backend.domain.User;
import com.mtsaas.backend.infrastructure.email.EmailService;
import com.mtsaas.backend.infrastructure.repository.EmailVerificationTokenRepository;
import com.mtsaas.backend.infrastructure.repository.UserRepository;
import com.mtsaas.backend.infrastructure.security.JwtService;
import com.mtsaas.backend.infrastructure.security.RateLimitingService;
import com.mtsaas.backend.infrastructure.security.RefreshTokenService;
import com.mtsaas.backend.infrastructure.security.TokenGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

        private final UserRepository userRepository;
        private final PasswordEncoder passwordEncoder;
        private final JwtService jwtService;
        private final AuthenticationManager authenticationManager;
        
        private final EmailVerificationTokenRepository tokenRepository;
        private final TokenGeneratorService tokenGeneratorService;
        private final EmailService emailService;
        private final RateLimitingService rateLimitingService;
        private final RefreshTokenService refreshTokenService;

        @Value("${app.frontend.url:http://localhost:3000}")
        private String frontendUrl;

        @Transactional(isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
        public AuthDto.AuthenticationResponse register(AuthDto.RegisterRequest request) {
                log.info("Registering new user: {}", request.getEmail());
                
                String email = request.getEmail();
                User user = null;
                
                Optional<User> existingUser = userRepository.findByEmailForUpdate(email);
                if (existingUser.isPresent()) {
                        log.warn("User already exists for email: {}", email);
                        throw new IllegalArgumentException("Email already registered");
                }
                
                user = new User();
                user.setEmail(email);
                user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
                user.setRole(Role.USER);
                user.setCredits(TrialLimitPolicy.SIGNED_IN_FREE_CREDITS);  // Give 10 free credits on signup
                user.setEmailVerified(false);
                user.setEmailVerifiedAt(null);
                user.setProvider("LOCAL");

                try {
                        user = userRepository.save(user);
                        log.info("✓ User created successfully: {} (ID: {})", user.getEmail(), user.getId());
                        
                        // Generate and save token
                        String rawToken = tokenGeneratorService.generateRawToken();
                        String hashedToken = tokenGeneratorService.hashToken(rawToken);
                        
                        EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                                .user(user)
                                .tokenHash(hashedToken)
                                .expiresAt(LocalDateTime.now().plusMinutes(30))
                                .build();
                                
                        tokenRepository.save(verificationToken);
                        
                        // Send email
                        String baseUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
                        String verificationUrl = baseUrl + "/auth/verify?token=" + rawToken;
                        emailService.sendVerificationEmail(email, verificationUrl);
                        log.info("✓ Verification email sent to: {}", email);
                        
                } catch (DataIntegrityViolationException e) {
                        log.error("⚠ DataIntegrityViolationException during user creation for {}: {}", email, e.getMessage());
                        user = userRepository.findByEmail(email)
                                .orElseThrow(() -> new RuntimeException("User creation failed: " + e.getMessage()));
                }

                log.info("✓ User registration completed (pending verification): {}", email);
                
                // Don't return token since they need to verify
                return new AuthDto.AuthenticationResponse(null, null, "Please check your email to verify your account.");
        }

        public AuthSession authenticate(AuthDto.AuthenticationRequest request) {
                authenticationManager.authenticate(
                                new UsernamePasswordAuthenticationToken(
                                                request.getEmail(),
                                                request.getPassword()));
                var user = userRepository.findByEmail(request.getEmail())
                                .orElseThrow();
                                
                if (!user.isEmailVerified()) {
                        throw new DisabledException("Please verify your email to log in.");
                }

                return createSessionForUser(user);
        }
        
        @Transactional
        public AuthSession verifyEmail(String rawToken) {
            String hashedToken = tokenGeneratorService.hashToken(rawToken);
            
            EmailVerificationToken token = tokenRepository.findByTokenHash(hashedToken)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification token."));
                
            if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
                tokenRepository.delete(token);
                throw new IllegalArgumentException("Verification token has expired. Please request a new one.");
            }
            
            User user = token.getUser();
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
            
            // Delete all tokens for this user
            tokenRepository.deleteAllByUser(user);
            
            log.info("✓ User email verified: {}", user.getEmail());
            
            // Generate token to log them in automatically
            AuthSession session = createSessionForUser(user);
            session.getResponse().setMessage("Email verified successfully!");
            return session;
        }

        @Transactional
        public AuthSession refresh(String rawRefreshToken) {
            var existingToken = refreshTokenService.findValidToken(rawRefreshToken)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token."));

            refreshTokenService.revoke(existingToken);
            return createSessionForUser(existingToken.getUser());
        }

        @Transactional
        public void logout(String rawRefreshToken) {
            if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
                refreshTokenService.revokeByRawToken(rawRefreshToken);
            }
        }

        @Transactional
        public AuthSession createSessionForUser(User user) {
            var jwtToken = jwtService.generateToken(new SecurityUser(user));
            String refreshToken = refreshTokenService.issueToken(user);
            return new AuthSession(new AuthDto.AuthenticationResponse(jwtToken, null, null), refreshToken);
        }

        public static class AuthSession {
            private final AuthDto.AuthenticationResponse response;
            private final String refreshToken;

            public AuthSession(AuthDto.AuthenticationResponse response, String refreshToken) {
                this.response = response;
                this.refreshToken = refreshToken;
            }

            public AuthDto.AuthenticationResponse getResponse() {
                return response;
            }

            public String getRefreshToken() {
                return refreshToken;
            }
        }
        
        @Transactional
        public void resendVerification(String email) {
            String rateLimitKey = "resend_verification:" + email;
            if (!rateLimitingService.isAllowed(rateLimitKey, 1, 60)) {
                throw new IllegalStateException("Please wait before requesting another verification email.");
            }
            
            User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
                
            if (user.isEmailVerified()) {
                throw new IllegalStateException("Email is already verified.");
            }
            
            // Invalidate old tokens
            tokenRepository.deleteAllByUser(user);
            
            // Generate new token
            String rawToken = tokenGeneratorService.generateRawToken();
            String hashedToken = tokenGeneratorService.hashToken(rawToken);
            
            EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                    .user(user)
                    .tokenHash(hashedToken)
                    .expiresAt(LocalDateTime.now().plusMinutes(30))
                    .build();
                    
            tokenRepository.save(verificationToken);
            
            // Send email
            String verificationUrl = frontendUrl + "/auth/verify?token=" + rawToken;
            emailService.sendVerificationEmail(email, verificationUrl);
            log.info("✓ Resent verification email to: {}", email);
        }
}
