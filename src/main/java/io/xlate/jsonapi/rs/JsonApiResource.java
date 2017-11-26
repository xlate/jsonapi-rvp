package io.xlate.jsonapi.rs;

import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Root;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

public abstract class JsonApiResource<E extends JsonApiEntity> {

    protected final transient Class<E> entityClass;

    @Context
    protected Request rsRequest;

    @Context
    protected UriInfo uriInfo;

    @Context
    protected SecurityContext security;

    @PersistenceContext
    protected EntityManager persistenceContext;

    @Inject
    protected Validator validator;

    CacheControl cacheControl = new CacheControl();

    public JsonApiResource(final Class<E> entityClass) {
        this.entityClass = entityClass;
        cacheControl.setPrivate(true);
    }

    protected abstract JsonApiSerializer<E> getSerializer();

    protected Response validate(JsonObject input) {
        Properties constraints = new Properties();

        try {
            constraints.load(getClass().getResourceAsStream("/jsonapi-constraints/" + getSerializer().getTypeName() + ".properties"));
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
                    violations.addAll(validator.validateValue(entityClass, fieldName, ((JsonString) jsonValue).getString()));
                    break;
                case TRUE:
                    violations.addAll(validator.validateValue(entityClass, fieldName, Boolean.TRUE));
                    break;
                case FALSE:
                    violations.addAll(validator.validateValue(entityClass, fieldName, Boolean.FALSE));
                    break;
                case NUMBER:
                    violations.addAll(validator.validateValue(entityClass, fieldName, ((JsonNumber) jsonValue).longValue()));
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
                                   .add("source", Json.createObjectBuilder()
                                                      .add("pointer", "data/attributes/" + attr))
                                   .add("detail", message));
                }
            }

            JsonObject jsonErrors = Json.createObjectBuilder().add("errors", errors).build();
            return Response.status(JsonApiStatus.UNPROCESSABLE_ENTITY).entity(jsonErrors).build();
        }

        return null;
    }

    /*
     * protected Account getUserAccount() { return ((AccountCallerPrincipal)
     * security.getUserPrincipal()).getAccount(); }
     *
     * protected String getUserFullName() { return getUserAccount().getName(); }
     */

    protected URI getUri(String method, long id) {
        UriBuilder builder = UriBuilder.fromUri(uriInfo.getBaseUri());
        builder.path(getClass());
        builder.path(getClass(), method);
        return builder.build(id);
    }

    protected static Response notFound() {
        return Response.status(Status.NOT_FOUND).build(); //EntityNotFoundMapper.notFound();
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

    protected Response created(E entity) {
        ResponseBuilder builder = Response.created(getUri("read", entity.getId()));
        return builder.entity(getSerializer().serialize(entity, uriInfo)).build();
    }

    protected Response seeOther(long id) {
        return Response.seeOther(getUri("read", id)).build();
    }

    @POST
    public Response create(JsonObject input) {
        Response validationResult = validate(input);

        if (validationResult != null) {
            return validationResult;
        }

        E entity = getSerializer().deserialize(input, null, false);
        persist(entity);

        return created(entity);
    }

    private void persist(final E entity) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        entity.setCreated("UNKNOWN", now);
        persistenceContext.persist(entity);
    }

    @GET
    public Response index() {
        CriteriaBuilder builder = persistenceContext.getCriteriaBuilder();
        CriteriaQuery<E> query = builder.createQuery(this.entityClass);
        Root<E> root = query.from(this.entityClass);
        query = query.select(root);

        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();

        if (params.containsKey("sort")) {
            String[] sortKeys = params.getFirst("sort").split(",");
            List<Order> order = new ArrayList<>(sortKeys.length);

            for (String sortKey : sortKeys) {
                if (sortKey.startsWith("-")) {
                    order.add(builder.desc(root.get(sortKey.substring(1))));
                } else {
                    order.add(builder.asc(root.get(sortKey)));
                }
            }

            query = query.orderBy(order);
        }

        TypedQuery<E> typedQuery = persistenceContext.createQuery(query);

        if (params.containsKey("page[offset]")) {
            typedQuery.setFirstResult(Integer.parseInt(params.getFirst("page[offset]")));
        }

        if (params.containsKey("page[limit]")) {
            typedQuery.setMaxResults(Integer.parseInt(params.getFirst("page[limit]")));
        }

        final List<E> results = typedQuery.getResultList();
        return ok(getSerializer().serialize(results, uriInfo));
    }

    protected List<E> findAll() {
        CriteriaBuilder builder = persistenceContext.getCriteriaBuilder();

        CriteriaQuery<E> query = builder.createQuery(entityClass);
        Root<E> entity = query.from(entityClass);
        query.select(entity);

        return persistenceContext.createQuery(query).getResultList();
    }

    protected <T> List<T> findByQuery(Class<T> resultClass, String queryName, Object... params) {
        return findByQuery(resultClass, queryName, 0, 100, params);
    }

    protected <T> List<T> findByQuery(Class<T> resultClass,
                                      String queryName,
                                      int startPosition,
                                      int maxResults,
                                      Object... params) {

        TypedQuery<T> query = persistenceContext.createNamedQuery(queryName, resultClass);

        int p = 0;

        for (Object param : params) {
            query.setParameter(++p, param);
        }

        return query.setFirstResult(startPosition)
                    .setMaxResults(maxResults)
                    .getResultList();
    }

    protected E find(final Long id) {
        E entity = persistenceContext.find(entityClass, id);
        if (entity != null) {
            return entity;
        }
        throw new NotFoundException();
    }

    @GET
    @Path("{id}")
    public Response read(@PathParam("id") final long id) {
        E entity = find(id);

        if (entity != null) {
            return ok(getSerializer().serialize(entity, uriInfo));
        }

        return notFound();
    }

    @PATCH
    @Path("{id}")
    public Response patch(@PathParam("id") long id, JsonObject input) {
        return update(id, input, true);
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") long id, final JsonObject input) {
        return update(id, input, false);
    }

    protected Response update(long id, JsonObject input, boolean patch) {
        Response validationResult = validate(input);

        if (validationResult != null) {
            return validationResult;
        }

        final E entity = find(id);

        if (entity != null) {
            JsonApiSerializer<E> ser = getSerializer();
            E deserialized = ser.deserialize(input, entity, patch);
            deserialized.setUpdated("UNKNOWN", new Timestamp(System.currentTimeMillis()));
            return ok(ser.serialize(persistenceContext.merge(deserialized), uriInfo));
        }

        return notFound();
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") final long id) {
        E entity = find(id);

        if (entity != null) {
            try {
                persistenceContext.remove(entity);
                persistenceContext.flush();
                return Response.noContent().build();
            } catch (PersistenceException e) {
                //Throwable cause = getRootCause(e);
                JsonObject response;
                /*String code;
                String title;

                if (cause instanceof SQLException) {
                    String sqlState = ((SQLException) cause).getSQLState();

                    if ("23000".equals(sqlState)) {
                        // Constraint violation;
                        title = "";
                    }

                    response = Json.createObjectBuilder()
                            .add("errors", Json.createArrayBuilder()
                                               .add(Json.createObjectBuilder()
                                                        .add("code", "D100")
                                                        .add("title", "Database error")
                                                        .add("detail", cause.getMessage())))
                            .build();
                } else {
                    response = Json.createObjectBuilder()
                            .add("errors", Json.createArrayBuilder()
                                               .add(Json.createObjectBuilder()
                                                        .add("code", "D999")
                                                        .add("title", "Unexpected error")))
                            .build();
                }*/

                response = Json.createObjectBuilder()
                        .add("errors", Json.createArrayBuilder()
                                           .add(Json.createObjectBuilder()
                                                    .add("title", "Unexpected error")))
                        .build();

                return Response.status(Status.CONFLICT).entity(response).build();
            }
        }

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
