package com.aquatrace.api.meters;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.time.LocalDate;

public record CreateMeterRequest(
        @NotBlank @Size(max = 100) String label,
        @NotNull MeterType meterType,
        @NotBlank @Size(max = 80) String unitId,
        @NotNull MeasurementUnit unitOfMeasure,
        @NotNull ReadingMode readingMode,
        @NotNull @Min(1) @Max(1440) Integer expectedIntervalMinutes,
        @NotNull LocalDate installationDate,
        @Valid MeterThresholdsRequest thresholds) {
}

