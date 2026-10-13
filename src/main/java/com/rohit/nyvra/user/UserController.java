package com.rohit.nyvra.user;

import com.rohit.nyvra.user.dto.UpdateDateOfBirthRequest;
import com.rohit.nyvra.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Resolves (and provisions) the caller's profile. */
    private final CurrentUserService currentUserService;
    /** Changes the caller's profile. */
    private final UserProfileService profileService;

    /**
     * Creates the controller.
     *
     * @param currentUserService resolves the authenticated user
     * @param profileService     changes the caller's profile
     */
    public UserController(CurrentUserService currentUserService, UserProfileService profileService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
    }

    /**
     * Returns the caller's profile, creating it on first call.
     *
     * @return the profile
     */
    @GetMapping("/me")
    @Operation(summary = "Current user profile", description = "Returns the caller's profile, creating it on first call.")
    public UserProfileResponse me() {
        return UserProfileResponse.from(currentUserService.currentUser());
    }

    /**
     * Sets or clears the caller's date of birth.
     *
     * @param request the new date of birth; {@code null} clears it
     * @return the updated profile
     */
    @PutMapping("/me/date-of-birth")
    @Operation(summary = "Set date of birth",
        description = "Sets or clears the caller's date of birth. The health score uses it for the age-based equity "
            + "target; without one it assumes an age.")
    public UserProfileResponse updateDateOfBirth(@RequestBody UpdateDateOfBirthRequest request) {
        return UserProfileResponse.from(profileService.updateDateOfBirth(request.dateOfBirth()));
    }
}
