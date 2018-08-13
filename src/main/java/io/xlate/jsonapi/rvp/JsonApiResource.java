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
import java.util.Map;
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
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.internal.persistence.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.rs.boundary.InternalContext;
import io.xlate.jsonapi.rvp.internal.rs.boundary.Responses;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;

@Path("")
@Consumes(JsonApiType.APPLICATION_JSONAPI)
@Produces(JsonApiType.APPLICATION_JSONAPI)
public abstract class JsonApiResource {

    @Inject
    @Any
    private Instance<JsonApiHandler<?>> handlers;

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

    protected Response validateParameters(JsonApiQuery params) {
        Set<ConstraintViolation<JsonApiQuery>> violations = validator.validate(params);

        if (!violations.isEmpty()) {
            return Responses.badRequest(violations);
        }

        return null;
    }

    protected Response validateEntity(String resourceType, String id, JsonObject input) {
        JsonApiRequest jsonApiRequest = new JsonApiRequest(request.getMethod(),
                                                           model.getEntityMeta(resourceType),
                                                           id,
                                                           input);

        try {
            Set<ConstraintViolation<JsonApiRequest>> violations = validator.validate(jsonApiRequest);

            if (!violations.isEmpty()) {
                return Responses.unprocessableEntity("Invalid JSON API Document Structure", violations);
            }
        } catch (Exception e) {
            return Responses.internalServerError(e);
        }

        return null;
    }

    @POST
    @Path("{resource-type}")
    public Response create(@PathParam("resource-type") String resourceType, JsonObject input) {
        InternalContext context = new InternalContext(request, uriInfo, resourceType, input);
        JsonApiHandler<?> handler = handlers.get();
        handler.onCreateRequest(context);

        Response validationResult = validateEntity(resourceType, null, input);

        if (validationResult != null) {
            return validationResult;
        }

        try {
            JsonObject response = persistence.create(resourceType, input, uriInfo);

            if (response != null) {
                return Responses.created(uriInfo, getClass(), resourceType, response);
            }

            return Responses.notFound();
        } catch (ConstraintViolationException e) {
            return Responses.unprocessableEntity("Invalid Input", e.getConstraintViolations());
        } catch (BadRequestException e) {
            return Responses.badRequest(e);
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return Responses.internalServerError(e);
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
                JsonApiQuery params = new JsonApiQuery(meta, id, uriInfo);

                Response validationResult = validateParameters(params);

                if (validationResult != null) {
                    return validationResult;
                }

                JsonObject response = persistence.fetch(params);

                if (response != null) {
                    return Responses.ok(cacheControl, request, response);
                }
            }

            return Responses.notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return Responses.internalServerError(e);
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
                return Responses.ok(cacheControl, request, response);
            }

            return Responses.notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return Responses.internalServerError(e);
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
        return Responses.notImplemented();
    }

    @SuppressWarnings("unused")
    @POST
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response addRelationship(@PathParam("resource-type") String resourceType,
                                    @PathParam("id") final String id,
                                    @PathParam("relationship-name") String relationshipName,
                                    final JsonObject input) {
        // TODO: implement relationship updates
        return Responses.notImplemented();
    }

    @SuppressWarnings("unused")
    @DELETE
    @Path("{resource-type}/{id}/relationships/{relationship-name}")
    public Response deleteRelationship(@PathParam("resource-type") String resourceType,
                                       @PathParam("id") final String id,
                                       @PathParam("relationship-name") String relationshipName,
                                       final JsonObject input) {
        // TODO: implement relationship updates
        return Responses.notImplemented();
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
                return Responses.ok(cacheControl, request, response);
            }

            return Responses.notFound();
        } catch (ConstraintViolationException e) {
            return Responses.unprocessableEntity("Invalid Input", e.getConstraintViolations());
        } catch (BadRequestException e) {
            return Responses.badRequest(e);
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return Responses.internalServerError(e);
        }
    }

    @DELETE
    @Path("{resource-type}/{id}")
    public Response delete(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        try {
            if (persistence.delete(resourceType, id)) {
                return Response.noContent().build();
            }
            return Responses.notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return Responses.internalServerError(e);
        }
    }
}
