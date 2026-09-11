package com.souldealers.crowdtracebackend.shared;

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
