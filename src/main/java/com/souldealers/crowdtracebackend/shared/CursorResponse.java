package com.souldealers.crowdtracebackend.shared;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CursorResponse", description = "Forward cursor metadata and content returned by a list endpoint")
public record CursorResponse<T>(List<T> content, int size, boolean hasNext, String nextCursor) {
    public CursorResponse {
        content = List.copyOf(content);
    }
}
