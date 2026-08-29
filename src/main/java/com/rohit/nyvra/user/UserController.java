package com.rohit.nyvra.user;

import com.rohit.nyvra.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User profile endpoints. {@code GET /api/v1/users/me} doubles as an auth smoke test:
 * it provisions the {@link UserProfile} on first authenticated call.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User")
@SecurityRequirement(name = "keycloak")
public class UserController {

    private final CurrentUserService currentUserService;

    public UserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile", description = "Returns the caller's profile, creating it on first call.")
    public UserProfileResponse me() {
        return UserProfileResponse.from(currentUserService.currentUser());
    }
}
