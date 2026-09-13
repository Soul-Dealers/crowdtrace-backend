package com.souldealers.crowdtracebackend.shared;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ApiResponse", description = "Standard successful CrowdTrace response envelope")
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        Object errors
) {

    public static <T> ApiResponse<T> success(
            T data,
            String message) {

        return new ApiResponse<>(
                true,
                message,
                data,
                null
        );
    }

}
