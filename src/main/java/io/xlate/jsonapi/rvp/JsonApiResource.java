/*******************************************************************************
 * Copyright (C) 2018 xlate.io LLC, http://www.xlate.io
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package io.xlate.jsonapi.rvp;

import static io.xlate.jsonapi.rvp.JsonApiResourceType.CONFIGURATION_KEY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.internal.DefaultJsonApiHandler;
import io.xlate.jsonapi.rvp.internal.JsonApiClientErrorException;
import io.xlate.jsonapi.rvp.internal.JsonApiHandlerChain;
import io.xlate.jsonapi.rvp.internal.persistence.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.rs.boundary.InternalContext;
import io.xlate.jsonapi.rvp.internal.rs.boundary.Responses;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;
import io.xlate.jsonapi.rvp.internal.validation.boundary.TransactionalValidator;

@Consumes(JsonApiMediaType.APPLICATION_JSONAPI)
@Produces(JsonApiMediaType.APPLICATION_JSONAPI)
public abstract class JsonApiResource {

    @Inject
    @Any
    private Instance<JsonApiHandler<?>> handlers;

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

    @Inject
    TransactionalValidator txValidator;

    CacheControl cacheControl = new CacheControl();

    private Class<?> resourceClass;
    private EntityMetamodel model;
    private PersistenceController persistence;

    @PostConstruct
    @SuppressWarnings("unchecked")
    void initialize() {
        if (config == null) {
            return;
        }

        Set<JsonApiResourceType<?>> resourceTypes;

        if (config.getProperties().containsKey(CONFIGURATION_KEY)) {
            resourceTypes = (Set<JsonApiResourceType<?>>) config.getProperty(CONFIGURATION_KEY);
        } else {
            resourceTypes = Collections.emptySet();
        }

        initialize(resourceTypes);
    }

    protected void initialize(Set<JsonApiResourceType<?>> resourceTypes) {
        resourceClass = this.getClass();

        while (resourceClass != null && !resourceClass.isAnnotationPresent(Path.class)) {
            resourceClass = resourceClass.getSuperclass();
        }

        if (resourceClass == null) {
            throw new IllegalStateException("Resource class missing @Path annotation");
        }

        model = new EntityMetamodel(resourceClass, resourceTypes, persistenceContext.getMetamodel());
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
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (meta == null) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                context.setEntityMeta(meta);
                handler.onRequest(context);

                Set<ConstraintViolation<?>> violations = validateEntity(resourceType, null, input);
                handler.afterValidation(context, violations);

                if (violations.isEmpty()) {
                    JsonObject response = persistence.create(context, handler);

                    if (response != null) {
                        context.setResponseEntity(response);
                        Responses.created(context, resourceClass);
                    } else {
                        Responses.notFound(context);
                    }
                } else {
                    Responses.unprocessableEntity(context, "Invalid JSON API Document Structure", violations);
                }
            }
        } catch (ConstraintViolationException e) {
            Responses.unprocessableEntity(context, "Invalid Input", e.getConstraintViolations());
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @GET
    @Path("{resource-type}")
    public Response index(@PathParam("resource-type") String resourceType) {
        InternalContext context = new InternalContext(request, uriInfo, security, resourceType);
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (meta == null) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                fetch(context, meta, handler);
            }
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @GET
    @Path("{resource-type}/{id}")
    public Response read(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id);
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (!isValidId(meta, id)) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                fetch(context, meta, handler);
            }
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    @GET
    @Path("{resource-type}/{id}/{relationship-name}")
    public Response readRelated(@PathParam("resource-type") String resourceType,
                                @PathParam("id") final String id,
                                @PathParam("relationship-name") String relationshipName) {

        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id, relationshipName);
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (!isValidId(meta, id)) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                fetch(context, meta, handler);
            }
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    void fetch(InternalContext context, EntityMeta meta, JsonApiHandler<?> handler) {
        JsonApiQuery params = new JsonApiQuery(this.model, meta, context.getResourceId(), context.getUriInfo());
        context.setQuery(params);

        handler.onRequest(context);

        Set<ConstraintViolation<?>> violations = validateParameters(params);

        if (violations.isEmpty()) {
            JsonObject response = persistence.fetch(context, handler);

            if (!context.hasResponse()) {
                if (response != null) {
                    Responses.ok(context, cacheControl, response);
                } else {
                    Responses.notFound(context);
                }
            }
        } else {
            Responses.badRequest(context, violations);
        }
    }

    @GET
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response readRelationship(@PathParam("resource-type") String resourceType,
                                     @PathParam("id") final String id,
                                     @PathParam("relationship-name") String relationshipName) {

        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id, relationshipName);
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (!isValidId(meta, id)) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                JsonObject response = persistence.getRelationships(context);

                if (response != null) {
                    Responses.ok(context, cacheControl, response);
                } else {
                    Responses.notFound(context);
                }
            }
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
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
    @Path("{resource-type}/{id}")
    public Response patchUpdate(@PathParam("resource-type") String resourceType,
                           @PathParam("id") String id,
                           final JsonObject input) {
        return update(resourceType, id, input);
    }

    @PUT
    @Path("{resource-type}/{id}")
    public Response update(@PathParam("resource-type") String resourceType,
                           @PathParam("id") String id,
                           final JsonObject input) {

        InternalContext context = new InternalContext(request, uriInfo, security, resourceType, id, input);
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (!isValidId(meta, id)) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                context.setEntityMeta(meta);
                handler.onRequest(context);
                Set<ConstraintViolation<?>> violations = validateEntity(resourceType, null, input);
                handler.afterValidation(context, violations);

                if (violations.isEmpty()) {
                    JsonObject response = persistence.update(context, handler);

                    if (!context.hasResponse()) {
                        if (response != null) {
                            Responses.ok(context, cacheControl, response);
                        } else {
                            Responses.notFound(context);
                        }
                    }
                } else {
                    Responses.unprocessableEntity(context, "Invalid JSON API Document Structure", violations);
                }
            }
        } catch (ConstraintViolationException e) {
            Responses.unprocessableEntity(context, "Invalid Input", e.getConstraintViolations());
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
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
        JsonApiHandler<?> handler = findHandler(resourceType, request.getMethod());

        try {
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (!isValidId(meta, id)) {
                Responses.notFound(context);
            } else if (!meta.isMethodAllowed(request.getMethod())) {
                Responses.methodNotAllowed(context);
            } else {
                handler.onRequest(context);
                handler.afterValidation(context, Collections.emptySet());

                if (persistence.delete(context, handler)) {
                    if (!context.hasResponse()) {
                        context.setResponseBuilder(Response.noContent());
                    }
                } else {
                    Responses.notFound(context);
                }
            }
        } catch (JsonApiClientErrorException e) {
            Responses.clientError(context, e);
        } catch (Exception e) {
            Responses.internalServerError(context, e);
        }

        handler.beforeResponse(context);
        return context.getResponseBuilder().build();
    }

    boolean isValidId(EntityMeta meta, String id) {
        try {
            return meta != null && meta.readId(id) != null;
        } catch (@SuppressWarnings("unused") Exception e) {
            return false;
        }
    }

    JsonApiHandler<?> findHandler(String resourceType, String httpMethod) {
        List<JsonApiHandler<?>> available = new ArrayList<>(2);

        for (JsonApiHandler<?> handler : handlers) {
            if (handler.isHandler(resourceType, httpMethod)) {
                available.add(handler);
            }
        }

        if (!available.isEmpty()) {
            return new JsonApiHandlerChain(available);
        }

        return new DefaultJsonApiHandler();
    }
}
