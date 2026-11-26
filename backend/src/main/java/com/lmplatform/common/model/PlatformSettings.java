package com.lmplatform.common.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformSettings(
        @NotBlank @Size(max = 80) String platformName,
        @NotBlank @Size(max = 500) String loginDescription,
        @Size(max = 300) String adminContact,
        long maxUploadBytes,
        @NotBlank @Size(max = 1000) String allowedExtensions
) {}
