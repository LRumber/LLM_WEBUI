package com.lmplatform.team.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Identifies an existing OAuth2 shadow user by local id, account, or user number. */
public record TeamMemberRequest(
        @NotBlank @Size(max = 128) String identifier,
        @Pattern(regexp = "admin|member") String role
) {
    public String resolvedRole() {
        return role == null || role.isBlank() ? "member" : role;
    }
}
