package com.lmplatform.admin.web;

/** Grants or revokes administration access for one OAuth2 shadow user. */
public record AdministratorStatusRequest(boolean enabled) {
}
