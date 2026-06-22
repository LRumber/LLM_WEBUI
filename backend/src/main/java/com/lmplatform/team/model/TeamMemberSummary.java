package com.lmplatform.team.model;

/** Existing shadow-user identity exposed only inside a team membership list. */
public record TeamMemberSummary(
        long userId,
        String accountNumber,
        String userNumber,
        String displayName,
        String role
) {
}
