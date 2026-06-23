package com.lmplatform.common.model;

/** Desired reverse-proxy bindings persisted for the host deployment script. */
public record DeploymentSettings(
        String domain,
        int httpPort,
        int httpsPort
) {
}
