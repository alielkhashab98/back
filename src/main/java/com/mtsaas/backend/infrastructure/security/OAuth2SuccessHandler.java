package com.mtsaas.backend.infrastructure.security;

import com.mtsaas.backend.application.service.AuthService;
import com.mtsaas.backend.domain.Role;
import com.mtsaas.backend.domain.User;
import com.mtsaas.backend.infrastructure.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    
    @Autowired
    @Lazy
    private AuthService authService;

    @Value("${app.jwt.refresh-token-expiration-ms:1209600000}")
    private long refreshTokenExpirationMs;

    @Value("${app.jwt.refresh-cookie-name:refresh_token}")
    private String refreshCookieName;

    @Value("${app.jwt.refresh-cookie-secure:false}")
    private boolean refreshCookieSecure;

    @Value("${app.jwt.refresh-cookie-same-site:Lax}")
    private String refreshCookieSameSite;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
            String email = oAuth2User.getAttribute("email");
            String name = oAuth2User.getAttribute("name");
            String firstName = oAuth2User.getAttribute("given_name");
            String lastName = oAuth2User.getAttribute("family_name");
            
            // Extract provider from OAuth2AuthenticationToken if available
            String provider = "OAUTH";
            if (authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) {
                provider = ((org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
            }

            log.info("OAuth2 authentication successful for email: {} from provider: {}", email, provider);

            // Use pessimistic write lock to prevent race conditions
            Optional<User> existingUser = userRepository.findByEmailForUpdate(email);
            User user;

            if (existingUser.isPresent()) {
                user = existingUser.get();
                log.info("Existing user found during OAuth2 auth: {}", user.getEmail());
            } else {
                // Create new user with all required fields set
                user = new User();
                user.setEmail(email);
                user.setFirstName(firstName != null ? firstName : (name != null && !name.isEmpty() ? name.split(" ")[0] : ""));
                user.setLastName(lastName != null ? lastName : (name != null && name.split(" ").length > 1 ? name.split(" ")[1] : ""));
                user.setProvider(provider != null ? provider.toUpperCase() : "GOOGLE");
                user.setRole(Role.USER);
                user.setPasswordHash(""); // No password for OAuth2 users
                user.setCredits(TrialLimitPolicy.SIGNED_IN_FREE_CREDITS); // Give 10 free credits on signup
                user.setEmailVerified(true); // OAuth2 emails are verified
                
                try {
                    user = userRepository.save(user);
                    log.info("✓ New OAuth2 user created: {} (ID: {}) via {}", user.getEmail(), user.getId(), user.getProvider());
                } catch (DataIntegrityViolationException e) {
                    // Should not happen with pessimistic locking, but handle as backup
                    log.error("⚠ DataIntegrityViolationException during OAuth2 user creation for {}: {}", email, e.getMessage());
                    user = userRepository.findByEmail(email)
                            .orElseThrow(() -> new RuntimeException("OAuth2 user creation failed: " + e.getMessage()));
                }

            }

            AuthService.AuthSession authSession = authService.createSessionForUser(user);
            response.addHeader("Set-Cookie", ResponseCookie.from(refreshCookieName, authSession.getRefreshToken())
                    .httpOnly(true)
                    .secure(refreshCookieSecure)
                    .sameSite(refreshCookieSameSite)
                    .path("/api/v1/auth")
                    .maxAge(refreshTokenExpirationMs / 1000)
                    .build()
                    .toString());

            // Generate JWT token and redirect
            String token = authSession.getResponse().getAccessToken();
            String redirectUrl = frontendUrl + "/auth/callback?token=" + token;
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
            
        } catch (Exception e) {
            log.error("OAuth2 authentication error: {}", e.getMessage(), e);
            try {
                response.sendRedirect(frontendUrl + "/auth/login?error=oauth2_failed");
            } catch (IOException ioe) {
                log.error("Failed to redirect on OAuth2 error: {}", ioe.getMessage());
            }
        }
    }
}
