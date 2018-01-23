package io.xlate.jsonapi.rs;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rs.internal.boundary.PersistenceController;
import io.xlate.jsonapi.rs.internal.entity.EntityMetamodel;

@Path("")
@Consumes(JsonApiType.JSONAPI)
@Produces(JsonApiType.JSONAPI)
public abstract class JsonApiResource {

    @Context
    protected Request rsRequest;

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

/*    protected Response validate(JsonObject input) {
        String constraintResourceName = "/jsonapi-constraints/" + getSerializer().getTypeName() + ".properties";
        Properties constraints = new Properties();

        try (InputStream stream = getClass().getResourceAsStream(constraintResourceName)) {
            if (stream != null) {
                constraints.load(stream);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return Response.serverError().build();
        }

        Set<ConstraintViolation<E>> violations = new LinkedHashSet<>();//validator.validate(entity);

        for (String sourceName : constraints.stringPropertyNames()) {
            String fieldName = constraints.getProperty(sourceName);
            JsonValue jsonValue = sourceName.indexOf('.') > -1 ? input : null;

            for (String sourceKey : sourceName.split("\\.")) {
                if (jsonValue instanceof JsonObject) {
                    JsonObject jsonObj = (JsonObject) jsonValue;

                    if (jsonObj.containsKey(sourceKey)) {
                        jsonValue = jsonObj.get(sourceKey);
                    } else {
                        jsonValue = null;
                        break;
                    }
                } else {
                    jsonValue = null;
                    break;
                }
            }

            if (jsonValue != null && jsonValue != input) {
                switch (jsonValue.getValueType()) {
                case STRING:
                    violations.addAll(validator.validateValue(entityClass,
                                                              fieldName,
                                                              ((JsonString) jsonValue).getString()));
                    break;
                case TRUE:
                    violations.addAll(validator.validateValue(entityClass, fieldName, Boolean.TRUE));
                    break;
                case FALSE:
                    violations.addAll(validator.validateValue(entityClass, fieldName, Boolean.FALSE));
                    break;
                case NUMBER:
                    violations.addAll(validator.validateValue(entityClass,
                                                              fieldName,
                                                              ((JsonNumber) jsonValue).longValue()));
                    break;
                case OBJECT:
                    // TODO: proper object validation
                    break;
                case NULL:
                default:
                    violations.addAll(validator.validateValue(entityClass, fieldName, null));
                    break;
                }
            } else {
                violations.addAll(validator.validateValue(entityClass, fieldName, null));
            }
        }

        if (!violations.isEmpty()) {
            Map<String, List<String>> errorMap = new LinkedHashMap<>();

            for (ConstraintViolation<?> violation : violations) {
                String property = violation.getPropertyPath().toString().replace('.', '/');

                if (!errorMap.containsKey(property)) {
                    errorMap.put(property, new ArrayList<String>(2));
                }

                errorMap.get(property).add(violation.getMessage());
            }

            JsonArrayBuilder errors = Json.createArrayBuilder();

            for (Entry<String, List<String>> property : errorMap.entrySet()) {
                final String attr = property.getKey();

                for (String message : property.getValue()) {
                    errors.add(Json.createObjectBuilder()
                                   .add("source",
                                        Json.createObjectBuilder()
                                            .add("pointer", "data/attributes/" + attr))
                                   .add("detail", message));
                }
            }

            JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();
            return Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors).build();
        }

        return null;
    }*/

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
                                                  .add("detail", "An error has occurred processing the request. Please try again later.")))
                                .build();

        return Response.status(statusCode).entity(errors).build();
    }

    protected static Response ok() {
        ResponseBuilder ok = Response.ok("{\"reason\": \"Success\"}");
        ok.type(MediaType.APPLICATION_JSON_TYPE);
        return ok.build();
    }

    protected Response ok(Object entity) {
        EntityTag etag = new EntityTag(Integer.toString(entity.hashCode()));
        ResponseBuilder builder;
        builder = rsRequest.evaluatePreconditions(etag);

        if (builder == null) {
            builder = Response.ok(entity);
            builder.tag(etag);
        }

        builder.cacheControl(cacheControl);

        return builder.build();
    }

    protected Response created(String resourceType, JsonObject jsonEntity) {
        ResponseBuilder builder = Response.created(getUri("read", resourceType, jsonEntity.getJsonObject("data").getString("id")));
        return builder.entity(jsonEntity).build();
    }

    protected Response seeOther(String resourceType, String id) {
        return Response.seeOther(getUri("read", resourceType, id)).build();
    }

    @POST
    @Path("{resource-type}")
    public Response create(@PathParam("resource-type") String resourceType, JsonObject input) {
        /*Response validationResult = validate(input);

        if (validationResult != null) {
            return validationResult;
        }*/

        /*E entity = getSerializer().deserialize(input, null, false);
        persist(entity);*/

        try {
            JsonObject response = persistence.create(resourceType, input, uriInfo);

            if (response != null) {
                return created(resourceType, response);
            }

            return notFound();
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
            JsonObject response = persistence.fetch(resourceType, id, uriInfo);

            if (response != null) {
                return ok(response);
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

    @PATCH
    @Path("{resource-type}/{id}")
    public Response patch(@PathParam("resource-type") String resourceType,
                          @PathParam("id") String id,
                          JsonObject input) {
        return update(resourceType, id, input, true);
    }

    @PUT
    @Path("{resource-type}/{id}")
    public Response update(@PathParam("resource-type") String resourceType,
                           @PathParam("id") String id,
                           final JsonObject input) {
        return update(resourceType, id, input, false);
    }

    protected Response update(String resourceType, String id, JsonObject input, boolean patch) {
        /*Response validationResult = validate(input);

        if (validationResult != null) {
            return validationResult;
        }*/

        try {
            JsonObject response = persistence.update(resourceType, id, input, uriInfo);

            if (response != null) {
                return ok(response);
            }

            return notFound();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return internalServerError(e);
        }
    }

    @DELETE
    @Path("{resource-type}/{id}")
    public Response delete(@PathParam("resource-type") String resourceType, @PathParam("id") final String id) {
        /*E entity = persistenceContext.find(entityClass, id);

        if (entity != null) {
            try {
                persistenceContext.remove(entity);
                persistenceContext.flush();
                return Response.noContent().build();
            } catch (@SuppressWarnings("unused") PersistenceException e) {
                JsonObject response;
                response = Json.createObjectBuilder()
                               .add("errors",
                                    Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                 .add("title", "Unexpected error")))
                               .build();

                return Response.status(Status.CONFLICT).entity(response).build();
            }
        }*/

        return notFound();
    }

    Throwable getRootCause(Throwable thrown) {
        Throwable cause = thrown;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }
}
