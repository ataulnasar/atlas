package com.atlas.core.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "atlas.upload")
public record UploadProperties(DataSize maxFileSize) {}
