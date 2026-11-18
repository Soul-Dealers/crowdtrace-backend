package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.governance.CommentModerationDetail;
import com.souldealers.crowdtracebackend.modules.governance.CommentModerationQueueItem;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

/** Response schemas for the comment moderation endpoints, derived from their records. */
@Component
class ModerationOpenApiSchemas implements OpenApiCustomizer {
    private static final String REF = "#/components/schemas/";

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(io.swagger.v3.oas.models.OpenAPI openApi) {
        var schemas = openApi.getComponents().getSchemas();
        for (Class<?> type : List.of(CommentModerationQueueItem.class, CommentModerationDetail.class)) {
            // Hand-written schemas (PublicUserResponse, ...) win over derived ones.
            ModelConverters.getInstance().readAll(new AnnotatedType(type)).forEach(schemas::putIfAbsent);
        }
        schemas.put("CommentModerationQueuePageResponse", new ObjectSchema()
                .addProperties("content", new ArraySchema().items(new Schema<>().$ref(REF + "CommentModerationQueueItem")))
                .addProperties("page", new IntegerSchema())
                .addProperties("size", new IntegerSchema())
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema())
                .addProperties("last", new BooleanSchema())
                .required(List.of("content", "page", "size", "totalElements", "totalPages", "last")));
        schemas.put("CommentModerationQueueListResponse", envelope("Reported comment page",
                "CommentModerationQueuePageResponse"));
        schemas.put("CommentModerationDetailApiResponse", envelope("Reported comment with its reports",
                "CommentModerationDetail"));
    }

    private static Schema envelope(String description, String data) {
        return new ObjectSchema().description(description)
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref(REF + data))
                .addProperties("errors", new ObjectSchema());
    }
}
