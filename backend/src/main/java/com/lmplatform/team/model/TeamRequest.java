package com.lmplatform.team.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Editable team metadata supplied by a designated team administrator. */
public record TeamRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {
}
