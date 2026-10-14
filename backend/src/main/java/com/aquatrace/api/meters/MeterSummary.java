package com.aquatrace.api.meters;

import java.time.LocalDate;
import java.time.Instant;

public record MeterSummary(String meterId, String siteId, String label, MeterType meterType,
        String unitId, MeasurementUnit unitOfMeasure, ReadingMode readingMode,
        Integer expectedIntervalMinutes, LocalDate installationDate, boolean enabled,
        Instant lastReadingAt, String freshness) {
}
