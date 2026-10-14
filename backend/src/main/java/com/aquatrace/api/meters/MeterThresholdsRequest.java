package com.aquatrace.api.meters;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record MeterThresholdsRequest(
        @DecimalMin(value = "0.0", inclusive = false) Double highUsageLitresPerHour,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String lowWindowStart,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String lowWindowEnd,
        @DecimalMin(value = "0.0", inclusive = false) Double lowUsageLitresPerHour,
        @Min(2) @Max(24) Integer sustainedIntervals) {
}
