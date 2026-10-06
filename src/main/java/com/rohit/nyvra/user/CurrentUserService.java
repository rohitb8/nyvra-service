package com.rohit.nyvra.user;

import com.rohit.nyvra.common.crypto.BlindIndexHasher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authenticated Keycloak subject to a {@link UserProfile}, provisioning one on first
 * request (just-in-time). This is the only place a {@code UserProfile} is created from a token.
 */
@Service
public class CurrentUserService {

    private final UserProfileRepository repository;
    private final BlindIndexHasher blindIndexHasher;

    public CurrentUserService(UserProfileRepository repository, BlindIndexHasher blindIndexHasher) {
        this.repository = repository;
        this.blindIndexHasher = blindIndexHasher;
    }

    @Transactional
    public UserProfile currentUser() {
        Jwt jwt = currentJwt();
        String subject = jwt.getSubject();
        return repository.findByKeycloakSubject(subject)
            .orElseGet(() -> {
                String email = jwt.getClaimAsString("email");
                return repository.save(new UserProfile(
                    subject, email, EmailBlindIndex.of(blindIndexHasher, email), jwt.getClaimAsString("name")));
            });
    }

    private static Jwt currentJwt() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt;
        }
        throw new IllegalStateException("No JWT in the security context — endpoint is not authenticated");
    }
}
