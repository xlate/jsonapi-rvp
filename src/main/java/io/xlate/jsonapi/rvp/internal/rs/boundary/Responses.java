package io.xlate.jsonapi.rvp.internal.rs.boundary;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.validation.ConstraintViolation;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiStatus;

public class Responses {

    private static final Logger logger = Logger.getLogger(Responses.class.getName());

    public static Response ok(CacheControl cacheControl, Request request, Object entity) {
        EntityTag etag = new EntityTag(Integer.toString(entity.hashCode()));
        ResponseBuilder builder;
        builder = request.evaluatePreconditions(etag);

        if (builder == null) {
            builder = Response.ok(entity);
            builder.tag(etag);
        }

        builder.cacheControl(cacheControl);

        return builder.build();
    }

    public static Response created(UriInfo uriInfo, Class<?> resource, String resourceType, JsonObject jsonEntity) {
        ResponseBuilder builder = Response.created(getUri(uriInfo,
                                                          resource,
                                                          "read",
                                                          resourceType,
                                                          jsonEntity.getJsonObject("data").getString("id")));

        return builder.entity(jsonEntity).build();
    }

    public static Response notFound() {
        Status notFound = Status.NOT_FOUND;

        JsonObject errors = Json.createObjectBuilder()
                                .add("errors",
                                     Json.createArrayBuilder()
                                         .add(Json.createObjectBuilder()
                                                  .add("status", String.valueOf(notFound.getStatusCode()))
                                                  .add("title", notFound.getReasonPhrase())
                                                  .add("detail", "The requested resource can not be found.")))
                                .build();

        return Response.status(notFound).entity(errors).build();
    }

    public static Response badRequest(BadRequestException e) {
        return error(e, Status.BAD_REQUEST, e.getMessage());
    }

    public static Response badRequest(Set<?> violationSet) {
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<?>> violations = (Set<ConstraintViolation<?>>) violationSet;

        Map<String, List<String>> errorMap = new LinkedHashMap<>();

        for (ConstraintViolation<?> violation : violations) {
            String property = violation.getPropertyPath().toString().replace('.', '/');

            if (property.isEmpty()) {
                continue;
            }

            if (!errorMap.containsKey(property)) {
                errorMap.put(property, new ArrayList<String>(2));
            }

            errorMap.get(property).add(violation.getMessage());
        }

        JsonArrayBuilder errors = Json.createArrayBuilder();

        for (Entry<String, List<String>> property : errorMap.entrySet()) {
            final String key = property.getKey();

            if (key.isEmpty()) {
                continue;
            }

            for (String message : property.getValue()) {
                errors.add(Json.createObjectBuilder()
                               .add("source", Json.createObjectBuilder().add("parameter", key))
                               .add("title", "Invalid Query Parameter")
                               .add("detail", message));
            }
        }

        JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();
        return Response.status(Status.BAD_REQUEST).entity(jsonErrors).build();
    }

    public static Response unprocessableEntity(String title, Set<?> violationSet) {
        Map<String, List<String>> errorMap = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<?>> violations = (Set<ConstraintViolation<?>>) violationSet;

        for (ConstraintViolation<?> violation : violations) {
            String property = violation.getPropertyPath().toString().replace('.', '/');

            if (!errorMap.containsKey(property)) {
                errorMap.put(property, new ArrayList<String>(2));
            }

            errorMap.get(property).add(violation.getMessage());
        }

        JsonArrayBuilder errors = Json.createArrayBuilder();

        for (Entry<String, List<String>> property : errorMap.entrySet()) {
            final String key = property.getKey();

            if (key.isEmpty()) {
                continue;
            }

            final String pointer;

            if (key.startsWith("/")) {
                pointer = key;
            } else {
                pointer = "/data/attributes/" + key;
            }

            for (String message : property.getValue()) {
                errors.add(Json.createObjectBuilder()
                               .add("source", Json.createObjectBuilder().add("pointer", pointer))
                               .add("title", title)
                               .add("detail", message));
            }
        }

        JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();
        return Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors).build();
    }

    public static Response internalServerError(Exception e) {
        return error(e,
                     Status.INTERNAL_SERVER_ERROR,
                     "An error has occurred processing the request. "
                             + "Please try again later.");
    }

    public static Response notImplemented() {
        return Response.status(Status.NOT_IMPLEMENTED).build();
    }

    private static URI getUri(UriInfo uriInfo, Class<?> resource, String method, String resourceType, String id) {
        UriBuilder builder = UriBuilder.fromUri(uriInfo.getBaseUri());
        builder.path(resource);
        builder.path(resource, method);
        return builder.build(resourceType, id);
    }

    private static Response error(Exception e, Status statusCode, String message) {
        logger.log(Level.WARNING, statusCode.getReasonPhrase(), e);

        JsonObject errors = Json.createObjectBuilder()
                                .add("errors",
                                     Json.createArrayBuilder()
                                         .add(Json.createObjectBuilder()
                                                  .add("status", String.valueOf(statusCode.getStatusCode()))
                                                  .add("title", statusCode.getReasonPhrase())
                                                  .add("detail", message)))
                                .build();

        return Response.status(statusCode).entity(errors).build();
    }
}
