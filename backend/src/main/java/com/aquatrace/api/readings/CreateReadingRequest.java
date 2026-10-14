package com.aquatrace.api.readings;

import com.aquatrace.api.meters.MeasurementUnit;
import com.aquatrace.api.meters.ReadingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateReadingRequest(
        @NotBlank @Size(max = 128) String eventId,
        @NotBlank @Size(max = 128) String sourceId,
        @NotNull Instant timestamp,
        @NotNull @PositiveOrZero Double value,
        @NotNull MeasurementUnit unitOfMeasure,
        @NotNull ReadingMode readingMode) {
}
