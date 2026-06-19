package com.lmplatform.admin.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credentials accepted only by the local administration login endpoint. */
public record AdminLoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 256) String password
) {
}
