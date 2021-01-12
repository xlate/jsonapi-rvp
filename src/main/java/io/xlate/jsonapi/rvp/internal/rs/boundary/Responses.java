package io.xlate.jsonapi.rvp.internal.rs.boundary;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.validation.ConstraintViolation;
import javax.validation.ElementKind;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.StatusType;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiStatus;
import io.xlate.jsonapi.rvp.internal.JsonApiErrorException;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.rs.entity.InternalContext;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiError;

public class Responses {

    private static final Logger logger = Logger.getLogger(Responses.class.getName());

    static class Error {
        final String message;
        final StatusType status;

        Error(String message, StatusType status) {
            this.message = message;
            this.status = status;
        }
    }

    private Responses() {
    }

    public static void ok(InternalContext context, CacheControl cacheControl, JsonObject entity) {
        EntityTag etag = new EntityTag(Integer.toString(entity.hashCode()));
        ResponseBuilder builder;
        builder = context.getRequest().evaluatePreconditions(etag);

        if (builder == null) {
            builder = Response.ok(entity);
            builder.tag(etag);
        }

        builder.cacheControl(cacheControl);
        context.setResponseBuilder(builder);
    }

    public static void created(InternalContext context, Class<?> resource, JsonObject entity) {
        ResponseBuilder builder = Response.created(getUri(context.getUriInfo(),
                                                          resource,
                                                          "read",
                                                          context.getResourceType(),
                                                          entity.getJsonObject("data").getString("id")));

        builder.entity(entity);
        context.setResponseBuilder(builder);
    }

    public static void notFound(InternalContext context) {
        Status notFound = Status.NOT_FOUND;
        JsonApiError error = new JsonApiError(notFound, "The requested resource can not be found.");
        JsonObject errors = errorsObject(Json.createArrayBuilder().add(error.toJson())).build();

        context.setResponseBuilder(Response.status(notFound).entity(errors));
    }

    public static void methodNotAllowed(InternalContext context) {
        Status notAllowed = Status.METHOD_NOT_ALLOWED;
        JsonApiError error = new JsonApiError(notAllowed, "Method not allowed for this resource");
        JsonObject errors = errorsObject(Json.createArrayBuilder().add(error.toJson())).build();

        context.setResponseBuilder(Response.status(notAllowed).entity(errors));
    }

    public static void error(InternalContext context, JsonApiErrorException e) {
        JsonArray errors = e.getErrors();

        if (errors != null) {
            JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();
            context.setResponseBuilder(Response.status(e.getStatus()).entity(jsonErrors));
        } else {
            error(context, e, e.getStatus(), e.getDetail());
        }
    }

    public static void badRequest(InternalContext context, Set<?> violationSet) {
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<?>> violations = (Set<ConstraintViolation<?>>) violationSet;

        JsonObject jsonErrors = errorsObject(violations.stream()
                                                       .filter(Responses::isPropertyViolation)
                                                       .map(JsonApiError::forParameterViolation)
                                                       .collect(Collectors.toList())).build();

        context.setResponseBuilder(Response.status(Status.BAD_REQUEST)
                                           .entity(jsonErrors));

    }

    static boolean isPropertyViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath().iterator().next().getKind() == ElementKind.PROPERTY;
    }

    public static void unprocessableEntity(InternalContext context, String title, Set<?> violationSet) {
        Map<String, List<Error>> errorMap = toErrorMap(context.getEntityMeta(), violationSet);
        JsonArrayBuilder errors = Json.createArrayBuilder();

        errorMap.entrySet()
                .stream()
                .filter(entry -> !entry.getKey().isBlank())
                .map(entry -> {
                    String key = entry.getKey();

                    if (!key.startsWith("/")) {
                        key = "/data/attributes/" + key;
                    }

                    return Map.entry(key, entry.getValue());
                })
                .forEach(entry -> {
                    for (Error error : entry.getValue()) {
                        JsonApiError jsonError = new JsonApiError(error.status,
                                                                  title,
                                                                  error.message,
                                                                  JsonApiError.Source.forPointer(entry.getKey()));

                        errors.add(jsonError.toJson());
                    }
                });

        JsonObject jsonErrors = errorsObject(errors).build();
        context.setResponseBuilder(Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors));
    }

    static Map<String, List<Error>> toErrorMap(EntityMeta meta, Set<?> violationSet) {
        Map<String, List<Error>> errorMap = new LinkedHashMap<>(violationSet.size());
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<?>> violations = (Set<ConstraintViolation<?>>) violationSet;

        for (ConstraintViolation<?> violation : violations) {
            String property = violation.getPropertyPath().toString().replace('.', '/');

            if (meta.getUniqueTuple(property) != null) {
                for (String constrained : meta.getUniqueTuple(property)) {
                    errorMap.computeIfAbsent(constrained, k -> new ArrayList<>(2))
                            .add(new Error("not unique", Status.CONFLICT));
                }
            } else {
                errorMap.computeIfAbsent(property, k -> new ArrayList<>(2))
                        .add(new Error(violation.getMessage(), null));
            }
        }

        return errorMap;
    }

    public static void internalServerError(InternalContext context, Exception e) {
        error(context,
              e,
              Status.INTERNAL_SERVER_ERROR,
              "An error has occurred processing the request. "
                      + "Please try again later.");
    }

    public static ResponseBuilder notImplemented() {
        return Response.status(Status.NOT_IMPLEMENTED);
    }

    private static URI getUri(UriInfo uriInfo, Class<?> resource, String method, String resourceType, String id) {
        UriBuilder builder = UriBuilder.fromUri(uriInfo.getBaseUri());
        builder.path(resource);
        builder.path(resource, method);
        return builder.build(resourceType, id);
    }

    private static void error(InternalContext context, Exception e, StatusType statusCode, String message) {
        logger.log(Level.WARNING, statusCode.getReasonPhrase(), e);

        JsonApiError error = new JsonApiError(statusCode, message);
        JsonObject errors = errorsObject(Json.createArrayBuilder().add(error.toJson())).build();
        context.setResponseBuilder(Response.status(statusCode).entity(errors));
    }

    private static JsonObjectBuilder errorsObject(Collection<JsonApiError> errors) {
        JsonArrayBuilder errorArray = Json.createArrayBuilder();
        errors.forEach(e -> errorArray.add(e.toJson()));
        return errorsObject(errorArray);
    }

    private static JsonObjectBuilder errorsObject(JsonArrayBuilder errors) {
        return Json.createObjectBuilder().add("errors", errors);
    }
}
