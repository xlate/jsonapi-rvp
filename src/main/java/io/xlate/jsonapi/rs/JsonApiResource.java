package io.xlate.jsonapi.rs;

import java.net.URI;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Root;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
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
import javax.ws.rs.core.GenericEntity;
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

    protected void validate(E entity) {
        Set<ConstraintViolation<E>> violations = validator.validate(entity);

        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
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

    private static EntityTag entityTag(Date updatedAt, long count) {
        StringBuilder buffer = new StringBuilder();

        if (updatedAt != null) {
            buffer.append(Long.toString(updatedAt.getTime()));
        } else {
            buffer.append('0');
        }

        buffer.append('.');

        buffer.append(Long.toString(count));

        return new EntityTag(Long.toHexString(buffer.toString().hashCode()));
    }

    @Deprecated
    protected Response ok(GenericEntity<List<E>> entityList) {
        Date maxUpdatedAt = null;

        for (E entity : entityList.getEntity()) {
            Date updatedAt = entity.getUpdatedAt();

            if (updatedAt == null) {
                continue;
            }

            if (maxUpdatedAt == null || maxUpdatedAt.before(updatedAt)) {
                maxUpdatedAt = updatedAt;
            }
        }

        ResponseBuilder builder;
        builder = Response.ok(entityList);
        builder.tag(entityTag(maxUpdatedAt, entityList.getEntity().size()));

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
        E entity = getSerializer().deserialize(input, null, false);
        validate(entity);
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

    protected ResponseBuilder evaluate(String queryName, String eTagHeader, Object... params) {
        if (eTagHeader == null) {
            return null;
        }

        Query query = persistenceContext.createNamedQuery(queryName);

        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
        }

        Object[] result;

        try {
            result = (Object[]) query.getSingleResult();
        } catch (@SuppressWarnings("unused") NoResultException e) {
            return null;
        }

        EntityTag eTag = entityTag((Date) result[0], (Long) result[1]);
        return rsRequest.evaluatePreconditions(eTag);
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
        final E entity = find(id);

        if (entity != null) {
            JsonApiSerializer<E> ser = getSerializer();
            E deserialized = ser.deserialize(input, entity, patch);
            validate(deserialized);
            deserialized.setUpdated("UNKNOWN", new Timestamp(System.currentTimeMillis()));
            return ok(ser.serialize(persistenceContext.merge(deserialized), uriInfo));
        }

        return notFound();
    }

    protected void remove(final E entity) {
        persistenceContext.remove(entity);
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
                Throwable cause = getRootCause(e);
                JsonObject response;

                if (cause instanceof SQLException) {
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
                }

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
