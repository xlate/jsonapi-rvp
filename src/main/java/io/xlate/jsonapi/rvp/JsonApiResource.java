/*******************************************************************************
 * Copyright (C) 2018 xlate.io LLC, http://www.xlate.io
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package io.xlate.jsonapi.rvp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.internal.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.entity.FetchParameters;
import io.xlate.jsonapi.rvp.internal.entity.JsonApiRequest;

@Path("")
@Consumes(JsonApiType.APPLICATION_JSONAPI)
@Produces(JsonApiType.APPLICATION_JSONAPI)
public abstract class JsonApiResource {

    @Context
    protected Request request;

    @Context
    protected UriInfo uriInfo;

    @Context
    protected Configuration config;

    @PersistenceContext
    protected EntityManager persistenceContext;

    @Inject
    protected Validator validator;

    CacheControl cacheControl = new CacheControl();

    private EntityMetamodel model;
    private PersistenceController persistence;

    private Map<String, Class<Object>> resourceTypes;

    @SuppressWarnings("unchecked")
    @PostConstruct
    void initialize() {
        if (config.getProperties().containsKey("io.xlate.jsonapi.rs.resourcetypes")) {
            resourceTypes = (Map<String, Class<Object>>) config.getProperty("io.xlate.jsonapi.rs.resourcetypes");
        } else {
            resourceTypes = Collections.emptyMap();
        }

        model = new EntityMetamodel(this.getClass(), resourceTypes, persistenceContext.getMetamodel());
        persistence = new PersistenceController(persistenceContext, model);
    }

    public JsonApiResource() {
        cacheControl.setPrivate(true);
    }

    protected Response validateParameters(FetchParameters params) {
        Set<ConstraintViolation<FetchParameters>> violations = validator.validate(params);

        if (!violations.isEmpty()) {
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

        return null;
    }

    protected static JsonObject mapErrors(String title, Set<?> violationSet) {
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

        return Json.createObjectBuilder().add("errors", errors).build();
    }

    protected Response validateEntity(String resourceType, String id, JsonObject input) {
        JsonApiRequest jsonApiRequest = new JsonApiRequest(request.getMethod(),
                                                           model.getEntityMeta(resourceType),
                                                           id,
                                                           input);

        try {
            Set<ConstraintViolation<JsonApiRequest>> violations = validator.validate(jsonApiRequest);

            if (!violations.isEmpty()) {
                JsonObject jsonErrors = mapErrors("Invalid JSON API Document Structure", violations);
                return Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors).build();
            }
        } catch (Exception e) {
            return internalServerError(e);
        }

        return null;
    }

    /*
     * protected Account getUserAccount() { return ((AccountCallerPrincipal)
     * security.getUserPrincipal()).getAccount(); }
     *
     * protected String getUserFullName() { return getUserAccount().getName(); }
     */

    protected URI getUri(String method, String resourceType, String id) {
        UriBuilder builder = UriBuilder.fromUri(uriInfo.getBaseUri());
        builder.path(getClass());
        builder.path(getClass(), method);
        return builder.build(resourceType, id);
    }

    protected static Response notFound() {
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

    protected Response internalServerError(Exception e) {
        Logger.getLogger(getClass().getName()).log(Level.WARNING, "Internal Server Error", e);

        Status statusCode = Status.INTERNAL_SERVER_ERROR;

        JsonObject errors = Json.createObjectBuilder()
                                .add("errors",
                                     Json.createArrayBuilder()
                                         .add(Json.createObjectBuilder()
                                                  .add("status", String.valueOf(statusCode.getStatusCode()))
                                                  .add("title", statusCode.getReasonPhrase())
                                                  .add("detail",
                                                       "An error has occurred processing the request. "
                                                               + "Please try again later.")))
                                .build();

        return Response.status(statusCode).entity(errors).build();
    }

    protected Response ok(Object entity) {
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

    protected Response created(String resourceType, JsonObject jsonEntity) {
        ResponseBuilder builder = Response.created(getUri("read",
                                                          resourceType,
                                                          jsonEntity.getJsonObject("data").getString("id")));
        return builder.entity(jsonEntity).build();
    }

    protected Response seeOther(String resourceType, String id) {
        return Response.seeOther(getUri("read", resourceType, id)).build();
    }

    @POST
    @Path("{resource-type}")
    public Response create(@PathParam("resource-type") String resourceType, JsonObject input) {
        Response validationResult = validateEntity(resourceType, null, input);

        if (validationResult != null) {
            return validationResult;
        }

        try {
            JsonObject response = persistence.create(resourceType, input, uriInfo);

            if (response != null) {
                return created(resourceType, response);
            }

            return notFound();
        } catch (ConstraintViolationException e) {
            Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
            JsonObject jsonErrors = mapErrors("Invalid Input", violations);
            return Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors).build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return internalServerError(e);
        }
    }

    @GET
    @Path("{resource-type}")
    public Response index(@PathParam("resource-type") String resourceType) {
        return read(resourceType, null);
    }

    @GET
    @Path("{resource-type}/{id}")
    public Response read(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (meta != null) {
                FetchParameters params = new FetchParameters(meta, id, uriInfo);

                Response validationResult = validateParameters(params);

                if (validationResult != null) {
                    return validationResult;
                }

                JsonObject response = persistence.fetch(params);

                if (response != null) {
                    return ok(response);
                }
            }

            return notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return internalServerError(e);
        }
    }

    @GET
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response readRelationship(@PathParam("resource-type") String resourceType,
                                     @PathParam("id") final String id,
                                     @PathParam("relationship-name") String relationshipName) {

        try {
            JsonObject response = persistence.getRelationships(resourceType,
                                                               uriInfo,
                                                               id,
                                                               relationshipName);

            if (response != null) {
                return ok(response);
            }

            return notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            e.printStackTrace();
            return internalServerError(e);
        }

    }

    @SuppressWarnings("unused")
    @PATCH
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response replaceRelationship(@PathParam("resource-type") String resourceType,
                                        @PathParam("id") final String id,
                                        @PathParam("relationship-name") String relationshipName,
                                        final JsonObject input) {
        // TODO: implement relationship updates
        return Response.status(Status.NOT_IMPLEMENTED).build();
    }

    @SuppressWarnings("unused")
    @POST
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response addRelationship(@PathParam("resource-type") String resourceType,
                                    @PathParam("id") final String id,
                                    @PathParam("relationship-name") String relationshipName,
                                    final JsonObject input) {
        // TODO: implement relationship updates
        return Response.status(Status.NOT_IMPLEMENTED).build();
    }

    @SuppressWarnings("unused")
    @DELETE
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response deleteRelationship(@PathParam("resource-type") String resourceType,
                                       @PathParam("id") final String id,
                                       @PathParam("relationship-name") String relationshipName,
                                       final JsonObject input) {
        // TODO: implement relationship updates
        return Response.status(Status.NOT_IMPLEMENTED).build();
    }

    @PATCH
    @PUT
    @Path("{resource-type}/{id}")
    public Response update(@PathParam("resource-type") String resourceType,
                           @PathParam("id") String id,
                           final JsonObject input) {
        Response validationResult = validateEntity(resourceType, id, input);

        if (validationResult != null) {
            return validationResult;
        }

        try {
            JsonObject response = persistence.update(resourceType, id, input, uriInfo);

            if (response != null) {
                return ok(response);
            }

            return notFound();
        } catch (ConstraintViolationException e) {
            Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
            JsonObject jsonErrors = mapErrors("Invalid Input", violations);
            return Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors).build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return internalServerError(e);
        }
    }

    @DELETE
    @Path("{resource-type}/{id}")
    public Response delete(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        try {
            if (persistence.delete(resourceType, id)) {
                return Response.noContent().build();
            }
            return notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return internalServerError(e);
        }
    }
}
