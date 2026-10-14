package com.aquatrace.api.readings;

import com.aquatrace.api.meters.MeasurementUnit;
import com.aquatrace.api.meters.ReadingMode;
import java.time.Instant;

public record ReadingSummary(String readingId, String siteId, String meterId, String eventId,
                             String sourceId, Instant timestamp, double value,
                             MeasurementUnit unitOfMeasure, ReadingMode readingMode, String quality) {
}
