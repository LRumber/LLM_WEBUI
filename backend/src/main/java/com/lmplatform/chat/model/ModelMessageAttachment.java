package com.lmplatform.chat.model;

/** Private object reference used only while building model context inside the core service. */
public record ModelMessageAttachment(String objectKey, String mediaType, String assetKind) {
}
