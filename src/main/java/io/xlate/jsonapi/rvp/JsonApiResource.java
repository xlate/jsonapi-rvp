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

import java.util.Collections;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import javax.ws.rs.BadRequestException;
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
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.internal.DefaultJsonApiHandler;
import io.xlate.jsonapi.rvp.internal.persistence.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.rs.boundary.InternalContext;
import io.xlate.jsonapi.rvp.internal.rs.boundary.Responses;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;

@Path("")
@Consumes(JsonApiMediaType.APPLICATION_JSONAPI)
@Produces(JsonApiMediaType.APPLICATION_JSONAPI)
public abstract class JsonApiResource {

    @Inject
    @Any
    private Instance<JsonApiHandler<?, ?>> handlers;

    @Context
    protected Request request;

    @Context
    protected UriInfo uriInfo;

    @Context
    protected SecurityContext security;

    @Context
    protected Configuration config;

    @PersistenceContext
    protected EntityManager persistenceContext;

    @Inject
    protected Validator validator;

    CacheControl cacheControl = new CacheControl();

    private EntityMetamodel model;
    private PersistenceController persistence;

    private Set<JsonApiResourceType<?>> resourceTypes;

    @SuppressWarnings("unchecked")
    @PostConstruct
    private void initialize() {
        if (config.getProperties().containsKey("io.xlate.jsonapi.rs.resourcetypes")) {
            resourceTypes = (Set<JsonApiResourceType<?>>) config.getProperty("io.xlate.jsonapi.rs.resourcetypes");
        } else {
            resourceTypes = Collections.emptySet();
        }

        model = new EntityMetamodel(this.getClass(), resourceTypes, persistenceContext.getMetamodel());
        persistence = new PersistenceController(persistenceContext, model);
    }

    public JsonApiResource() {
        cacheControl.setPrivate(true);
    }

    protected Set<ConstraintViolation<?>> validateParameters(JsonApiQuery params) {
        return Collections.unmodifiableSet(validator.validate(params));
    }

    protected Set<ConstraintViolation<?>> validateEntity(String resourceType, String id, JsonObject input) {
        JsonApiRequest jsonApiRequest = new JsonApiRequest(request.getMethod(),
                                                           model.getEntityMeta(resourceType),
                                                           id,
                                                           input);

        return Collections.unmodifiableSet(validator.validate(jsonApiRequest));
    }

    @POST
    @Path("{resource-type}")
    public Response create(@PathParam("resource-type") String resourceType, JsonObject input) {
        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, input);
        JsonApiHandler<?, ?> handler = findHandler(resourceType, request.getMethod());

        try {
            handler.onRequest(context);

            Set<ConstraintViolation<?>> violations = validateEntity(resourceType, null, input);
            handler.afterValidation(context, violations);

            if (violations.isEmpty()) {
                JsonObject response = persistence.create(context, handler);

                if (response != null) {
                    context.setResponseEntity(response);
                    Responses.created(context, getClass());
                } else {
                    Responses.notFound(context);
                }
            } else {
                Responses.unprocessableEntity(context, "Invalid JSON API Document Structure", violations);
            }
        } catch (ConstraintViolationException e) {
            Responses.unprocessableEntity(context, "Invalid Input", e.getConstraintViolations());
        } catch (BadRequestException e) {
            // TODO: don't throw these from internal
            Responses.badRequest(context, e);
        } catch (WebApplicationException e) {
            // TODO: don't throw these from internal
            return e.getResponse();
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @GET
    @Path("{resource-type}")
    public Response index(@PathParam("resource-type") String resourceType) {
        return read(resourceType, null);
    }

    @GET
    @Path("{resource-type}/{id}")
    public Response read(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id);
        JsonApiHandler<?, ?> handler = findHandler(resourceType, request.getMethod());

        try {
            handler.onRequest(context);

            EntityMeta meta = model.getEntityMeta(resourceType);

            if (meta != null) {
                JsonApiQuery params = new JsonApiQuery(meta, id, uriInfo);
                context.setQuery(params);
                Set<ConstraintViolation<?>> violations = validateParameters(params);

                if (violations.isEmpty()) {
                    JsonObject response = persistence.fetch(context);

                    if (response != null) {
                        Responses.ok(context, cacheControl, response);
                    } else {
                        Responses.notFound(context);
                    }
                } else {
                    Responses.badRequest(context, violations);
                }
            } else {
                Responses.notFound(context);
            }
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @GET
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response readRelationship(@PathParam("resource-type") String resourceType,
                                     @PathParam("id") final String id,
                                     @PathParam("relationship-name") String relationshipName) {

        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id, relationshipName);
        JsonApiHandler<?, ?> handler = findHandler(resourceType, request.getMethod());

        try {
            JsonObject response = persistence.getRelationships(resourceType,
                                                               uriInfo,
                                                               id,
                                                               relationshipName);

            if (response != null) {
                Responses.ok(context, cacheControl, response);
            } else {
                Responses.notFound(context);
            }
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @SuppressWarnings("unused")
    @PATCH
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response replaceRelationship(@PathParam("resource-type") String resourceType,
                                        @PathParam("id") final String id,
                                        @PathParam("relationship-name") String relationshipName,
                                        final JsonObject input) {
        // TODO: implement relationship updates
        return Responses.notImplemented().build();
    }

    @SuppressWarnings("unused")
    @POST
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response addRelationship(@PathParam("resource-type") String resourceType,
                                    @PathParam("id") final String id,
                                    @PathParam("relationship-name") String relationshipName,
                                    final JsonObject input) {
        // TODO: implement relationship updates
        return Responses.notImplemented().build();
    }

    @SuppressWarnings("unused")
    @DELETE
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response deleteRelationship(@PathParam("resource-type") String resourceType,
                                       @PathParam("id") final String id,
                                       @PathParam("relationship-name") String relationshipName,
                                       final JsonObject input) {
        // TODO: implement relationship updates
        return Responses.notImplemented().build();
    }

    @PATCH
    @PUT
    @Path("{resource-type}/{id}")
    public Response update(@PathParam("resource-type") String resourceType,
                           @PathParam("id") String id,
                           final JsonObject input) {

        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id, input);
        JsonApiHandler<?, ?> handler = findHandler(resourceType, request.getMethod());

        try {
            Set<?> violations = validateEntity(resourceType, null, input);

            if (violations.isEmpty()) {
                JsonObject response = persistence.update(resourceType, id, input, uriInfo);

                if (response != null) {
                    Responses.ok(context, cacheControl, response);
                } else {
                    Responses.notFound(context);
                }
            } else {
                Responses.unprocessableEntity(context, "Invalid JSON API Document Structure", violations);
            }
        } catch (ConstraintViolationException e) {
            Responses.unprocessableEntity(context, "Invalid Input", e.getConstraintViolations());
        } catch (BadRequestException e) {
            Responses.badRequest(context, e);
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @DELETE
    @Path("{resource-type}/{id}")
    public Response delete(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id);
        JsonApiHandler<?, ?> handler = findHandler(resourceType, request.getMethod());

        try {
            if (persistence.delete(resourceType, id)) {
                context.setResponseBuilder(Response.noContent());
            } else {
                Responses.notFound(context);
            }
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    JsonApiHandler<?, ?> findHandler(String resourceType, String method) {
        for (JsonApiHandler<?, ?> handler : handlers) {
            if (resourceType.equals(handler.getResourceType())) {
                //TODO: check method should be handled
                Class<?> type = handler.getClass();
                return handler;
            }
        }

        return new DefaultJsonApiHandler(resourceType);
    }
}
