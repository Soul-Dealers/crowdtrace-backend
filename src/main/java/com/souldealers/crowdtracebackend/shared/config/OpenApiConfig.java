package com.souldealers.crowdtracebackend.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    private static final String PLANNED_DESCRIPTION =
            "Contract placeholder only. This operation is planned and not implemented in the current runtime. "
                    + "No controller exists yet; implementation is scheduled for a later domain phase.";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CrowdTrace API")
                        .version("0.1.0")
                        .description("Backend API for the CrowdTrace missing-persons registry."))
                .servers(List.of(new Server()
                        .url("http://localhost:8080")
                        .description("Local development server")))
                .tags(List.of(
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Operations")
                                .description("Application health probes"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Identity")
                                .description("Current identity endpoints"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Case Submission")
                                .description("Registered-user case submission and evidence uploads"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Authentication")
                                .description("Planned authentication endpoints"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Public Cases")
                                .description("Planned public case registry endpoints"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Administration")
                                .description("Planned moderator and admin endpoints")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .schemas(reusableSchemas()))
                .paths(contractPaths());
    }

    private Map<String, Schema> reusableSchemas() {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.put("ProblemDetail", problemDetailSchema());
        schemas.put("ApiResponse", apiResponseSchema());
        schemas.put("PagedResponse", pagedResponseSchema());
        schemas.put("UserResponse", userResponseSchema());
        schemas.put("UserPageResponse", userPageResponseSchema());
        schemas.put("UserListResponse", userListResponseSchema());
        schemas.put("VerificationRequestResponse", verificationRequestResponseSchema());
        schemas.put("AdminVerificationRequestResponse", adminVerificationRequestResponseSchema());
        schemas.put("VerificationRequestListResponse", verificationRequestListResponseSchema());
        schemas.put("AdminVerificationPageResponse", adminVerificationPageResponseSchema());
        schemas.put("AdminVerificationListResponse", adminVerificationListResponseSchema());
        schemas.put("AdminVerificationDecisionResponse", adminVerificationDecisionResponseSchema());
        schemas.put("HealthResponse", healthResponseSchema());
        schemas.put("CaseFileUploadRequest", caseFileUploadRequestSchema());
        schemas.put("CaseFileMetadataResponse", caseFileMetadataResponseSchema());
        schemas.put("CaseFileUploadResponse", caseResponseEnvelope("Safe uploaded-file metadata",
                "#/components/schemas/CaseFileMetadataResponse"));
        schemas.put("CaseSubmissionRequest", caseSubmissionRequestSchema());
        schemas.put("SensitiveDetailsInput", sensitiveDetailsInputSchema());
        schemas.put("ConsentInput", consentInputSchema());
        schemas.put("CaseSubmissionResponse", caseSubmissionResponseSchema());
        schemas.put("CaseSubmissionApiResponse", caseResponseEnvelope("Case submission confirmation",
                "#/components/schemas/CaseSubmissionResponse"));
        schemas.put("AdminCaseSummaryResponse", adminCaseSummarySchema());
        schemas.put("ReviewQueuePageResponse", reviewQueuePageSchema());
        schemas.put("ReviewQueueListResponse", caseResponseEnvelope("Review queue page",
                "#/components/schemas/ReviewQueuePageResponse"));
        schemas.put("ReviewEvidenceResponse", reviewEvidenceSchema());
        schemas.put("DuplicateMatchResponse", duplicateMatchSchema());
        schemas.put("CaseReviewHistoryResponse", reviewHistorySchema());
        schemas.put("ReviewCaseDetailResponse", reviewCaseDetailSchema());
        schemas.put("ReviewCaseDetailApiResponse", caseResponseEnvelope("Case with evidence, matches and history",
                "#/components/schemas/ReviewCaseDetailResponse"));
        schemas.put("CaseReviewDecisionResponse", reviewDecisionSchema());
        schemas.put("CaseReviewDecisionApiResponse", caseResponseEnvelope("Recorded review decision",
                "#/components/schemas/CaseReviewDecisionResponse"));
        schemas.put("UpdateCaseStatusRequest", lifecycleStatusRequestSchema());
        schemas.put("SetClosingStatementRequest", closingStatementRequestSchema());
        schemas.put("TakeDownCaseRequest", takeDownRequestSchema());
        schemas.put("RestoreCaseRequest", restoreRequestSchema());
        schemas.put("SensitiveDetailsResponse", sensitiveDetailsResponseSchema());
        schemas.put("PublicCaseResponse", publicCaseResponseSchema());
        schemas.put("PublicCasePageResponse", publicCasePageSchema());
        schemas.put("PublicCaseListResponse", caseResponseEnvelope("Public case page",
                "#/components/schemas/PublicCasePageResponse"));
        schemas.put("PublicCaseApiResponse", caseResponseEnvelope("Public case detail",
                "#/components/schemas/PublicCaseResponse"));
        schemas.put("ReporterCaseResponse", reporterCaseResponseSchema());
        schemas.put("ReporterCaseApiResponse", caseResponseEnvelope("Reporter-owned case detail",
                "#/components/schemas/ReporterCaseResponse"));
        schemas.put("CaseLifecycleResponse", caseLifecycleResponseSchema());
        schemas.put("CaseLifecycleApiResponse", caseResponseEnvelope("Case lifecycle mutation result",
                "#/components/schemas/CaseLifecycleResponse"));
        return schemas;
    }

    private Schema lifecycleStatusRequestSchema() {
        return new ObjectSchema().required(List.of("expectedCaseVersion", "caseStatus"))
                .addProperties("expectedCaseVersion", new IntegerSchema().format("int64").minimum(BigDecimal.ZERO))
                .addProperties("caseStatus", new StringSchema()
                        ._enum(List.of("MISSING", "FOUND_SAFE", "FOUND_DECEASED", "CLOSED")));
    }

    private Schema closingStatementRequestSchema() {
        return new ObjectSchema().required(List.of("expectedCaseVersion", "closingStatement"))
                .addProperties("expectedCaseVersion", new IntegerSchema().format("int64").minimum(BigDecimal.ZERO))
                .addProperties("closingStatement", new StringSchema().maxLength(2000)
                        .description("Unicode-stripped, non-blank public statement."));
    }

    private Schema takeDownRequestSchema() {
        return new ObjectSchema().required(List.of("expectedCaseVersion", "reason"))
                .addProperties("expectedCaseVersion", new IntegerSchema().format("int64").minimum(BigDecimal.ZERO))
                .addProperties("reason", new StringSchema().maxLength(2000));
    }

    private Schema restoreRequestSchema() {
        return new ObjectSchema().required(List.of("expectedCaseVersion"))
                .addProperties("expectedCaseVersion", new IntegerSchema().format("int64").minimum(BigDecimal.ZERO));
    }

    private Schema caseLifecycleResponseSchema() {
        return new ObjectSchema().required(List.of("caseId", "caseVersion", "reviewStatus", "caseStatus", "occurredAt"))
                .addProperties("caseId", new IntegerSchema().format("int64"))
                .addProperties("caseVersion", new IntegerSchema().format("int64"))
                .addProperties("reviewStatus", new StringSchema())
                .addProperties("caseStatus", new StringSchema())
                .addProperties("closingStatement", nullableString())
                .addProperties("resolvedAt", nullableString().format("date-time"))
                .addProperties("closedAt", nullableString().format("date-time"))
                .addProperties("occurredAt", new StringSchema().format("date-time"));
    }

    private Schema publicCaseResponseSchema() {
        return new ObjectSchema()
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("fullName", new StringSchema())
                .addProperties("age", new IntegerSchema().format("int32"))
                .addProperties("gender", new StringSchema())
                .addProperties("lastSeenDate", new StringSchema().format("date"))
                .addProperties("region", new StringSchema())
                .addProperties("lastSeenLocation", nullableString())
                .addProperties("physicalDescription", nullableString())
                .addProperties("clothing", nullableString())
                .addProperties("circumstances", nullableString())
                .addProperties("caseStatus", new StringSchema())
                .addProperties("closingStatement", nullableString())
                .addProperties("publicContactNumber", nullableString())
                .addProperties("approvedAt", new StringSchema().format("date-time"))
                .addProperties("resolvedAt", nullableString().format("date-time"))
                .addProperties("presentation", new StringSchema()._enum(List.of("STANDARD", "DECEASED_NOTICE")));
    }

    private Schema reporterCaseResponseSchema() {
        return new ObjectSchema()
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("fullName", new StringSchema())
                .addProperties("age", new IntegerSchema().format("int32"))
                .addProperties("gender", new StringSchema())
                .addProperties("lastSeenDate", new StringSchema().format("date"))
                .addProperties("region", new StringSchema())
                .addProperties("lastSeenLocation", nullableString())
                .addProperties("physicalDescription", nullableString())
                .addProperties("clothing", nullableString())
                .addProperties("circumstances", nullableString())
                .addProperties("publicContactNumber", nullableString())
                .addProperties("reviewStatus", new StringSchema())
                .addProperties("caseStatus", nullableString())
                .addProperties("closingStatement", nullableString())
                .addProperties("submittedAt", new StringSchema().format("date-time"))
                .addProperties("approvedAt", nullableString().format("date-time"))
                .addProperties("sensitiveDetails", nullableRef("#/components/schemas/SensitiveDetailsResponse"))
                .addProperties("version", new IntegerSchema().format("int64"))
                .addProperties("resolvedAt", nullableString().format("date-time"))
                .addProperties("closedAt", nullableString().format("date-time"));
    }

    private Schema nullableString() {
        return new JsonSchema().types(new LinkedHashSet<>(List.of("string", "null")));
    }

    private Schema nullableRef(String reference) {
        return new JsonSchema().anyOf(List.of(
                new JsonSchema().$ref(reference),
                new JsonSchema().types(new LinkedHashSet<>(List.of("null")))));
    }

    private Schema publicCasePageSchema() {
        return new ObjectSchema()
                .addProperties("content", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/PublicCaseResponse")))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema adminCaseSummarySchema() {
        return new ObjectSchema()
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("fullName", new StringSchema())
                .addProperties("age", new IntegerSchema().format("int32"))
                .addProperties("region", new StringSchema())
                .addProperties("reviewStatus", new StringSchema()._enum(List.of("SUBMITTED", "UNDER_REVIEW")))
                .addProperties("priorityMinor", new BooleanSchema())
                .addProperties("duplicateFlag", new BooleanSchema())
                .addProperties("submittedAt", new StringSchema().format("date-time"));
    }

    private Schema reviewQueuePageSchema() {
        return new ObjectSchema()
                .addProperties("content", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/AdminCaseSummaryResponse")))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema reviewEvidenceSchema() {
        return new ObjectSchema()
                .description("File metadata only; accessAvailable stays false until signed access exists. "
                        + "No URL or storage key is returned.")
                .addProperties("file", new Schema<>().$ref("#/components/schemas/CaseFileMetadataResponse"))
                .addProperties("accessAvailable", new BooleanSchema());
    }

    private Schema duplicateMatchSchema() {
        return new ObjectSchema()
                .addProperties("matchedCaseId", new IntegerSchema().format("int64"))
                .addProperties("confidence", new IntegerSchema().format("int32"))
                .addProperties("nameSimilarity", new IntegerSchema().format("int32")
                        .description("Per mille."))
                .addProperties("dayDifference", new IntegerSchema().format("int32"))
                .addProperties("reasons", new ArraySchema().items(new StringSchema()))
                .addProperties("algorithmVersion", new StringSchema())
                .addProperties("detectedAt", new StringSchema().format("date-time"));
    }

    private Schema reviewHistorySchema() {
        return new ObjectSchema()
                .description("A past decision. Notes are admin-only.")
                .addProperties("reviewId", new IntegerSchema().format("int64"))
                .addProperties("decision", new StringSchema()._enum(List.of("APPROVED", "REJECTED")))
                .addProperties("reviewerId", new IntegerSchema().format("int64"))
                .addProperties("reviewSource", new StringSchema())
                .addProperties("reviewNotes", new StringSchema())
                .addProperties("decidedAt", new StringSchema().format("date-time"));
    }

    private Schema reviewCaseDetailSchema() {
        Schema caseDetails = new ObjectSchema()
                .description("Admin projection. Files are listed once, as evidence; there is no files property.")
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("reporterId", new IntegerSchema().format("int64"))
                .addProperties("reporterAccountEmail", new StringSchema())
                .addProperties("fullName", new StringSchema())
                .addProperties("age", new IntegerSchema().format("int32"))
                .addProperties("gender", new StringSchema())
                .addProperties("lastSeenDate", new StringSchema().format("date"))
                .addProperties("region", new StringSchema())
                .addProperties("lastSeenLocation", new StringSchema())
                .addProperties("physicalDescription", new StringSchema())
                .addProperties("clothing", new StringSchema())
                .addProperties("circumstances", new StringSchema())
                .addProperties("publicContactNumber", new StringSchema())
                .addProperties("reviewStatus", new StringSchema())
                .addProperties("caseStatus", nullableString())
                .addProperties("closingStatement", nullableString())
                .addProperties("priorityMinor", new BooleanSchema())
                .addProperties("duplicateFlag", new BooleanSchema())
                .addProperties("version", new IntegerSchema().format("int64")
                        .description("Send as expectedCaseVersion when deciding."))
                .addProperties("submittedAt", new StringSchema().format("date-time"))
                .addProperties("approvedAt", nullableString().format("date-time"))
                .addProperties("resolvedAt", nullableString().format("date-time"))
                .addProperties("closedAt", nullableString().format("date-time"))
                .addProperties("takedownReason", nullableString())
                .addProperties("takenDownBy", new JsonSchema().types(new LinkedHashSet<>(List.of("integer", "null")))
                        .format("int64"))
                .addProperties("takenDownAt", nullableString().format("date-time"))
                .addProperties("sensitiveDetails", new ObjectSchema())
                .addProperties("consents", new ArraySchema().items(new ObjectSchema()));
        return new ObjectSchema()
                .addProperties("caseDetails", caseDetails)
                .addProperties("evidence", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/ReviewEvidenceResponse")))
                .addProperties("duplicateMatches", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/DuplicateMatchResponse")))
                .addProperties("reviewHistory", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/CaseReviewHistoryResponse")));
    }

    private Schema reviewDecisionSchema() {
        return new ObjectSchema()
                .addProperties("reviewId", new IntegerSchema().format("int64"))
                .addProperties("caseId", new IntegerSchema().format("int64"))
                .addProperties("reviewStatus", new StringSchema()._enum(List.of("APPROVED", "REJECTED")))
                .addProperties("caseVersion", new IntegerSchema().format("int64"))
                .addProperties("reviewerId", new IntegerSchema().format("int64"))
                .addProperties("reviewNotes", new StringSchema())
                .addProperties("decidedAt", new StringSchema().format("date-time"));
    }

    private Schema caseFileUploadRequestSchema() {
        return new ObjectSchema()
                .description("Upload one file before submission. Reports accept PDF, JPEG or PNG; photos accept JPEG "
                        + "or PNG. The maximum file size is 10 MB.")
                .required(List.of("file", "purpose"))
                .addProperties("file", new StringSchema().format("binary"))
                .addProperties("purpose", new StringSchema()._enum(List.of("REPORT", "PHOTO")))
                .addProperties("sha256", new StringSchema()
                        .description("Optional SHA-256 checksum checked against the uploaded content."));
    }

    private Schema caseFileMetadataResponseSchema() {
        return new ObjectSchema()
                .description("Safe file metadata; storage keys and checksums are never returned.")
                .required(List.of("id", "purpose", "visibility", "contentType", "sizeBytes", "uploadedAt"))
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("purpose", new StringSchema()._enum(List.of("REPORT", "PHOTO")))
                .addProperties("visibility", new StringSchema()._enum(List.of("PRIVATE", "PUBLIC")))
                .addProperties("contentType", new StringSchema())
                .addProperties("sizeBytes", new IntegerSchema().format("int64"))
                .addProperties("uploadedAt", new StringSchema().format("date-time"));
    }

    private Schema caseSubmissionRequestSchema() {
        return new ObjectSchema()
                .description("Public case details, private sensitive details, explicit consent and owned upload ids. "
                        + "File ids must be distinct across both slots and match the slot's REPORT or PHOTO purpose.")
                .required(List.of("fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
                        "physicalDescription", "clothing", "circumstances", "publicContactNumber",
                        "sensitiveDetails", "consent", "reportFileIds"))
                .addProperties("fullName", new StringSchema().minLength(1).maxLength(255))
                .addProperties("age", new IntegerSchema().format("int32")
                        .minimum(BigDecimal.ZERO).maximum(BigDecimal.valueOf(130))
                        .description("Age at disappearance. Cases under 18 receive minor priority at submission."))
                .addProperties("gender", new StringSchema()._enum(List.of("MALE", "FEMALE", "UNKNOWN")))
                .addProperties("lastSeenDate", new StringSchema().format("date")
                        .description("From 1900-01-01 through today's date in the server's UTC clock, inclusive."))
                .addProperties("region", new StringSchema()._enum(List.of("AHAFO", "ASHANTI", "BONO", "BONO_EAST",
                        "CENTRAL", "EASTERN", "GREATER_ACCRA", "NORTH_EAST", "NORTHERN", "OTI", "SAVANNAH",
                        "UPPER_EAST", "UPPER_WEST", "VOLTA", "WESTERN", "WESTERN_NORTH")))
                .addProperties("lastSeenLocation", new StringSchema().minLength(1).maxLength(500))
                .addProperties("physicalDescription", new StringSchema().minLength(1))
                .addProperties("clothing", new StringSchema().minLength(1))
                .addProperties("circumstances", new StringSchema().minLength(1))
                .addProperties("publicContactNumber", new StringSchema().minLength(1).maxLength(32))
                .addProperties("sensitiveDetails", new Schema<>().$ref("#/components/schemas/SensitiveDetailsInput"))
                .addProperties("consent", new Schema<>().$ref("#/components/schemas/ConsentInput"))
                .addProperties("reportFileIds", new ArraySchema().minItems(1)
                        .items(new IntegerSchema().format("int64").minimum(BigDecimal.ONE)))
                .addProperties("photoFileIds", new ArraySchema().maxItems(5)
                        .items(new IntegerSchema().format("int64").minimum(BigDecimal.ONE)));
    }

    private Schema sensitiveDetailsInputSchema() {
        return new ObjectSchema()
                .description("Private sensitive details, excluded from public responses.")
                .required(List.of("reporterRelationship"))
                .addProperties("reporterRelationship", new StringSchema().minLength(1).maxLength(100))
                .addProperties("medicalConditions", new StringSchema())
                .addProperties("knownAssociates", new StringSchema())
                .addProperties("vehicleInfo", new StringSchema())
                .addProperties("socialMediaHandles", new StringSchema());
    }

    private Schema consentInputSchema() {
        return new ObjectSchema()
                .description("Explicit consent to sensitive-data collection. The server stores the authenticated "
                        + "user, current policy version, source and acceptance time.")
                .required(List.of("accepted", "version", "source"))
                .addProperties("accepted", new BooleanSchema()._enum(List.of(true)))
                .addProperties("version", new StringSchema().minLength(1).maxLength(32)
                        .description("Must match crowdtrace.consent.sensitive-data-version; an outdated version returns 400."))
                .addProperties("source", new StringSchema()._enum(List.of("WEB", "MOBILE", "API")));
    }

    private Schema caseSubmissionResponseSchema() {
        return new ObjectSchema()
                .description("Submission confirmation containing only the numeric case reference, review status and time.")
                .required(List.of("caseId", "reviewStatus", "submittedAt"))
                .addProperties("caseId", new IntegerSchema().format("int64"))
                .addProperties("reviewStatus", new StringSchema()._enum(List.of("SUBMITTED")))
                .addProperties("submittedAt", new StringSchema().format("date-time"));
    }

    private Schema caseResponseEnvelope(String description, String dataReference) {
        return new ObjectSchema().description(description)
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref(dataReference))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema problemDetailSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem details returned for API errors.")
                .addProperties("type", new StringSchema().format("uri"))
                .addProperties("title", new StringSchema())
                .addProperties("status", new IntegerSchema().format("int32"))
                .addProperties("detail", new StringSchema())
                .addProperties("instance", new StringSchema().format("uri"))
                .addProperties("code", new StringSchema())
                .addProperties("correlationId", new StringSchema())
                .addProperties("errors", new ObjectSchema());
    }

    private Schema apiResponseSchema() {
        return new ObjectSchema()
                .description("Standard successful CrowdTrace response envelope.")
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref("#/components/schemas/PagedResponse"))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema pagedResponseSchema() {
        return new ObjectSchema()
                .description("Page metadata and content returned by list endpoints.")
                .addProperties("content", new ArraySchema().items(new ObjectSchema()))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema userResponseSchema() {
        return new ObjectSchema()
                .description("User projection returned by the current user listing endpoint.")
                .addProperties("displayName", new StringSchema())
                .addProperties("email", new StringSchema().format("email"))
                .addProperties("role", new StringSchema())
                .addProperties("accountStatus", new StringSchema())
                .addProperties("createdAt", new StringSchema().format("date-time"))
                .addProperties("verified", new BooleanSchema())
                .addProperties("badgeType", new StringSchema());
    }

    private Schema userPageResponseSchema() {
        return new ObjectSchema()
                .description("Concrete page shape returned by the current user listing endpoint.")
                .addProperties("content", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/UserResponse")))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema userListResponseSchema() {
        return new ObjectSchema()
                .description("Concrete response envelope returned by the current user listing endpoint.")
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref("#/components/schemas/UserPageResponse"))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema healthResponseSchema() {
        return new ObjectSchema()
                .description("Spring Boot health probe response.")
                .addProperties("status", new StringSchema())
                .addProperties("components", new ObjectSchema());
    }

    private Schema verificationRequestResponseSchema() {
        return new ObjectSchema()
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("verificationType", new StringSchema())
                .addProperties("evidenceReference", new StringSchema())
                .addProperties("status", new StringSchema())
                .addProperties("createdAt", new StringSchema().format("date-time"))
                .addProperties("reviewedAt", new StringSchema().format("date-time"));
    }

    private Schema adminVerificationRequestResponseSchema() {
        return new ObjectSchema()
                .addProperties("id", new IntegerSchema().format("int64"))
                .addProperties("userId", new IntegerSchema().format("int64"))
                .addProperties("displayName", new StringSchema())
                .addProperties("verificationType", new StringSchema())
                .addProperties("evidenceReference", new StringSchema())
                .addProperties("status", new StringSchema())
                .addProperties("reviewNotes", new StringSchema())
                .addProperties("createdAt", new StringSchema().format("date-time"))
                .addProperties("reviewedAt", new StringSchema().format("date-time"))
                .addProperties("revokedAt", new StringSchema().format("date-time"))
                .addProperties("revocationNotes", new StringSchema());
    }

    private Schema verificationRequestListResponseSchema() {
        return new ObjectSchema()
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/VerificationRequestResponse")))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema adminVerificationPageResponseSchema() {
        return new ObjectSchema()
                .addProperties("content", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/AdminVerificationRequestResponse")))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema adminVerificationListResponseSchema() {
        return new ObjectSchema()
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref("#/components/schemas/AdminVerificationPageResponse"))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema adminVerificationDecisionResponseSchema() {
        return new ObjectSchema()
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref("#/components/schemas/AdminVerificationRequestResponse"))
                .addProperties("errors", new ObjectSchema());
    }

    private Paths contractPaths() {
        return new Paths()
                .addPathItem("/actuator/health/liveness", healthPath(
                        "Liveness probe", "Returns the current liveness status."))
                .addPathItem("/actuator/health/readiness", healthPath(
                        "Readiness probe", "Returns the current readiness status, including database health."))
                .addPathItem("/api/user/case-files", new PathItem().post(caseFileUploadOperation()))
                .addPathItem("/api/user/cases", new PathItem().post(caseSubmissionOperation()))
                .addPathItem("/api/user/cases/{id}", new PathItem().get(reporterCaseDetailOperation()))
                .addPathItem("/api/user/cases/{id}/status", new PathItem().patch(caseLifecycleOperation(
                        "Change a case outcome", "UpdateCaseStatusRequest", "Change case outcome")))
                .addPathItem("/api/user/cases/{id}/closing-statement", new PathItem().put(caseLifecycleOperation(
                        "Set a closing statement", "SetClosingStatementRequest", "Set closing statement")))
                .addPathItem("/api/v1/admin/cases/{id}/status", new PathItem().patch(adminCaseLifecycleOperation(
                        "Correct a case outcome", "UpdateCaseStatusRequest", "Correct case outcome")))
                .addPathItem("/api/v1/admin/cases/{id}/closing-statement", new PathItem().put(
                        adminCaseLifecycleOperation("Set a closing statement", "SetClosingStatementRequest",
                                "Set closing statement")))
                .addPathItem("/api/v1/admin/cases/{id}/takedown", new PathItem().post(
                        adminCaseLifecycleOperation("Take down an approved case", "TakeDownCaseRequest",
                                "Hide an approved case while preserving its outcome and lifecycle data")))
                .addPathItem("/api/v1/admin/cases/{id}/restore", new PathItem().post(
                        adminCaseLifecycleOperation("Restore a taken-down case", "RestoreCaseRequest",
                                "Restore a taken-down case without changing its outcome or takedown details")))
                .addPathItem("/api/public/cases", new PathItem().get(publicPlannedOperation(
                        "Public Cases", "List public cases", "Planned public case listing operation.")
                        .responses(new ApiResponses().addApiResponse("200", jsonResponse(
                                "Contract placeholder only; no runtime response is available.",
                                "#/components/schemas/PublicCaseListResponse")))))
                .addPathItem("/api/public/cases/{caseId}", new PathItem().get(
                        publicPlannedOperation("Public Cases", "Get public case", "Planned public case detail operation.")
                                .parameters(List.of(caseIdParameter()))
                                .responses(new ApiResponses().addApiResponse("200", jsonResponse(
                                        "Contract placeholder only; no runtime response is available.",
                                        "#/components/schemas/PublicCaseApiResponse")))));
    }

    private Operation reporterCaseDetailOperation() {
        return new Operation().tags(List.of("Case Submission"))
                .summary("Get a reporter case")
                .description("Returns the authenticated reporter's case detail and current case version. Foreign and "
                        + "unknown cases both return 404.")
                .security(List.of(new SecurityRequirement().addList("bearerAuth")))
                .parameters(List.of(new Parameter().name("id").in("path").required(true)
                        .description("Case identifier.").schema(new IntegerSchema().format("int64"))))
                .responses(new ApiResponses()
                        .addApiResponse("200", jsonResponse("Reporter case detail",
                                "#/components/schemas/ReporterCaseApiResponse"))
                        .addApiResponse("401", caseProblemResponse("Authentication required"))
                        .addApiResponse("403", caseProblemResponse("Registered-user role required"))
                        .addApiResponse("404", caseProblemResponse("Unknown or foreign case")));
    }

    private Schema sensitiveDetailsResponseSchema() {
        return new ObjectSchema()
                .addProperties("reporterRelationship", nullableString())
                .addProperties("medicalConditions", nullableString())
                .addProperties("knownAssociates", nullableString())
                .addProperties("vehicleInfo", nullableString())
                .addProperties("socialMediaHandles", nullableString());
    }

    private Operation caseLifecycleOperation(String summary, String requestSchema, String description) {
        return lifecycleOperation("Case Submission", summary, requestSchema, description, false);
    }

    private Operation adminCaseLifecycleOperation(String summary, String requestSchema, String description) {
        return lifecycleOperation("Administration", summary, requestSchema, description, true);
    }

    private Operation lifecycleOperation(String tag, String summary, String requestSchema, String description,
                                         boolean admin) {
        return new Operation().tags(List.of(tag)).summary(summary)
                .description(description + ". Requires " + (admin ? "moderator or Super Admin" : "registered user")
                        + " authentication. State and expected version are checked under the case lock. Errors use "
                        + "ProblemDetail: 400 invalid body, 401 unauthenticated, 403 role required, 404 unknown or "
                        + "foreign case, 409 invalid state, stale version or transition.")
                .security(List.of(new SecurityRequirement().addList("bearerAuth")))
                .parameters(List.of(new Parameter().name("id").in("path").required(true)
                        .description("Case identifier.").schema(new IntegerSchema().format("int64"))))
                .requestBody(new RequestBody().required(true).content(new Content()
                        .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/" + requestSchema)))))
                .responses(new ApiResponses()
                        .addApiResponse("200", jsonResponse("Case lifecycle result",
                                "#/components/schemas/CaseLifecycleApiResponse"))
                        .addApiResponse("400", caseProblemResponse("Invalid request"))
                        .addApiResponse("401", caseProblemResponse("Authentication required"))
                        .addApiResponse("403", caseProblemResponse(admin ? "Moderator role required"
                                : "Registered-user role required"))
                        .addApiResponse("404", caseProblemResponse(admin ? "Unknown case"
                                : "Unknown or foreign case"))
                        .addApiResponse("409", caseProblemResponse("Invalid state, stale version or transition"))
                        .addApiResponse("500", caseProblemResponse("Audit failure")));
    }

    private Operation caseFileUploadOperation() {
        return new Operation().tags(List.of("Case Submission"))
                .summary("Upload case evidence")
                .description("Requires a registered-user JWT. Upload a private REPORT or public PHOTO before submitting "
                        + "a case. The per-user limit is 20 successful uploads per 24-hour window; validation-rejected "
                        + "uploads are refunded. Keep the returned file id for its submission slot.")
                .security(List.of(new SecurityRequirement().addList("bearerAuth")))
                .requestBody(new RequestBody().required(true).content(new Content()
                        .addMediaType("multipart/form-data", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/CaseFileUploadRequest")))))
                .responses(new ApiResponses()
                        .addApiResponse("201", jsonResponse("File uploaded", "#/components/schemas/CaseFileUploadResponse"))
                        .addApiResponse("400", caseProblemResponse("Missing part, invalid purpose, file content or checksum"))
                        .addApiResponse("401", caseProblemResponse("Authentication required"))
                        .addApiResponse("403", caseProblemResponse("Registered-user authorization required"))
                        .addApiResponse("413", caseProblemResponse("File exceeds the multipart upload limit"))
                        .addApiResponse("429", retryableCaseProblemResponse("Per-user upload quota exhausted"))
                        .addApiResponse("503", retryableCaseProblemResponse("Upload quota store unavailable")));
    }

    private Operation caseSubmissionOperation() {
        return new Operation().tags(List.of("Case Submission"))
                .summary("Submit a case for review")
                .description("Requires a registered-user JWT. Atomically records the case as SUBMITTED, sensitive details, "
                        + "current-version consent and owned unattached reports/photos. Duplicates never block submission: "
                        + "CaseSubmittedEvent is published inside the transaction for best-effort AFTER_COMMIT detection. "
                        + "There is no replay; the consumer writes duplicate metadata in REQUIRES_NEW. Minor priority is "
                        + "set immediately from age at disappearance.")
                .security(List.of(new SecurityRequirement().addList("bearerAuth")))
                .requestBody(new RequestBody().required(true).content(new Content()
                        .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/CaseSubmissionRequest")))))
                .responses(new ApiResponses()
                        .addApiResponse("201", jsonResponse("Case submitted", "#/components/schemas/CaseSubmissionApiResponse"))
                        .addApiResponse("400", caseProblemResponse("Invalid fields, missing report or consent, outdated consent "
                                + "version, duplicate ids, wrong file purpose or photo cap exceeded"))
                        .addApiResponse("401", caseProblemResponse("Authentication required"))
                        .addApiResponse("403", caseProblemResponse("Registered-user authorization required"))
                        .addApiResponse("404", caseProblemResponse("A referenced file is missing, deleted or belongs to another user"))
                        .addApiResponse("409", caseProblemResponse("A referenced file is already attached")));
    }

    private io.swagger.v3.oas.models.responses.ApiResponse caseProblemResponse(String description) {
        return new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
                .content(new Content().addMediaType("application/problem+json", new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
    }

    private io.swagger.v3.oas.models.responses.ApiResponse retryableCaseProblemResponse(String description) {
        return caseProblemResponse(description).addHeaderObject("Retry-After", new Header()
                .description("Seconds until the request may be retried.")
                .schema(new IntegerSchema().format("int64").minimum(BigDecimal.ONE)));
    }

    private PathItem healthPath(String summary, String description) {
        return new PathItem().get(new Operation()
                .tags(List.of("Operations"))
                .summary(summary)
                .description(description)
                .security(List.of())
                .responses(new ApiResponses().addApiResponse("200", jsonResponse(
                        "Health status", "#/components/schemas/HealthResponse"))
                        .addApiResponse("default", jsonResponse(
                                "Problem details", "#/components/schemas/ProblemDetail"))));
    }

    private Operation publicPlannedOperation(String tag, String summary, String description) {
        return plannedOperation(tag, summary, description).security(List.of());
    }

    private Operation plannedOperation(String tag, String summary, String description) {
        Operation operation = new Operation()
                .tags(List.of(tag))
                .summary(summary)
                .description(description + " " + PLANNED_DESCRIPTION)
                .responses(new ApiResponses().addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()
                        .description("Contract placeholder only; no runtime response is available.")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse"))))));
        operation.addExtension("x-crowdtrace-status", "planned");
        return operation;
    }

    private Parameter caseIdParameter() {
        return new Parameter()
                .name("caseId")
                .in("path")
                .required(true)
                .description("Planned case identifier.")
                .schema(new StringSchema());
    }

    private io.swagger.v3.oas.models.responses.ApiResponse jsonResponse(String description, String schemaReference) {
        return new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref(schemaReference))));
    }
}
