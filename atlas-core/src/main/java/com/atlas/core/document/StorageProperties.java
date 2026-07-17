package com.atlas.core.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.storage")
public record StorageProperties(String path) {}
