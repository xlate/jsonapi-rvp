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
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.validation.ConstraintViolation;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiStatus;
import io.xlate.jsonapi.rvp.internal.JsonApiClientErrorException;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;

public class Responses {

    private static final Logger logger = Logger.getLogger(Responses.class.getName());

    public static void ok(InternalContext context, CacheControl cacheControl, JsonObject entity) {
        EntityTag etag = new EntityTag(Integer.toString(entity.hashCode()));
        ResponseBuilder builder;
        builder = context.getRequest().evaluatePreconditions(etag);

        if (builder == null) {
            context.setResponseEntity(entity);
            builder = Response.ok(entity);
            builder.tag(etag);
        }

        builder.cacheControl(cacheControl);
        context.setResponseBuilder(builder);
    }

    public static void created(InternalContext context, Class<?> resource) {
        ResponseBuilder builder = Response.created(getUri(context.getUriInfo(),
                                                          resource,
                                                          "read",
                                                          context.getResourceType(),
                                                          context.getResponseEntity().getJsonObject("data")
                                                                 .getString("id")));

        builder.entity(context.getResponseEntity());
        context.setResponseBuilder(builder);
    }

    public static void notFound(InternalContext context) {
        Status notFound = Status.NOT_FOUND;

        JsonObject errors = Json.createObjectBuilder()
                                .add("errors",
                                     Json.createArrayBuilder()
                                         .add(Json.createObjectBuilder()
                                                  .add("status", String.valueOf(notFound.getStatusCode()))
                                                  .add("title", notFound.getReasonPhrase())
                                                  .add("detail", "The requested resource can not be found.")))
                                .build();

        context.setResponseEntity(errors);
        context.setResponseBuilder(Response.status(notFound).entity(errors));
    }

    public static void clientError(InternalContext context, JsonApiClientErrorException e) {
        JsonArray errors = e.getErrors();

        if (errors != null) {
            JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();
            context.setResponseEntity(jsonErrors);
            context.setResponseBuilder(Response.status(e.getStatus()).entity(errors));
        } else {
            error(context, e, e.getStatus(), e.getDetail());
        }
    }

    public static void badRequest(InternalContext context, Set<?> violationSet) {
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

        context.setResponseEntity(jsonErrors);
        context.setResponseBuilder(Response.status(Status.BAD_REQUEST).entity(jsonErrors));

    }

    public static void unprocessableEntity(InternalContext context, String title, Set<?> violationSet) {
        class Error {
            String message;
            String status;

            Error(String message, String status) {
                this.message = message;
                this.status = status;
            }
        }
        Map<String, List<Error>> errorMap = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<?>> violations = (Set<ConstraintViolation<?>>) violationSet;
        EntityMeta meta = context.getEntityMeta();

        for (ConstraintViolation<?> violation : violations) {
            String property = violation.getPropertyPath().toString().replace('.', '/');

            if (meta.getUniqueTuple(property) != null) {
                for (String constrained : meta.getUniqueTuple(property)) {
                    if (!errorMap.containsKey(constrained)) {
                        errorMap.put(constrained, new ArrayList<Error>(2));
                    }

                    errorMap.get(constrained).add(new Error("not unique",
                                                            String.valueOf(Status.CONFLICT.getStatusCode())));
                }
            } else {
                if (!errorMap.containsKey(property)) {
                    errorMap.put(property, new ArrayList<Error>(2));
                }

                errorMap.get(property).add(new Error(violation.getMessage(), null));
            }
        }

        JsonArrayBuilder errors = Json.createArrayBuilder();

        for (Entry<String, List<Error>> property : errorMap.entrySet()) {
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

            for (Error error : property.getValue()) {
                JsonObjectBuilder builder = Json.createObjectBuilder()
                                                .add("source", Json.createObjectBuilder().add("pointer", pointer))
                                                .add("title", title)
                                                .add("detail", error.message);

                if (error.status != null) {
                    builder.add("status", error.status);
                }

                errors.add(builder);
            }
        }

        JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();

        context.setResponseEntity(jsonErrors);
        context.setResponseBuilder(Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors));
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

    private static void error(InternalContext context, Exception e, Status statusCode, String message) {
        logger.log(Level.WARNING, statusCode.getReasonPhrase(), e);

        JsonObject errors = Json.createObjectBuilder()
                                .add("errors",
                                     Json.createArrayBuilder()
                                         .add(Json.createObjectBuilder()
                                                  .add("status", String.valueOf(statusCode.getStatusCode()))
                                                  .add("title", statusCode.getReasonPhrase())
                                                  .add("detail", message)))
                                .build();

        context.setResponseEntity(errors);
        context.setResponseBuilder(Response.status(statusCode).entity(errors));
    }
}
