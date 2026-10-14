package com.aquatrace.api.ingestion;

import java.time.Instant;

public record IngestionJobSummary(String jobId, String siteId, String status, String fileName,
                                  long fileSizeBytes, String checksumSha256, Instant createdAt,
                                  Integer inputRows, Integer acceptedRows, Integer rejectedRows) {
}
