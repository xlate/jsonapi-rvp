package io.xlate.jsonapi.rs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.persistence.EntityGraph;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.Subgraph;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;
import javax.persistence.metamodel.ManagedType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.PluralAttribute;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
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

    static String toJsonName(String attributeName) {
        StringBuilder jsonName = new StringBuilder(attributeName);
        Pattern p = Pattern.compile("([A-Z])[a-z]");
        Matcher m = p.matcher(jsonName);

        while (m.find()) {
            char replacement = m.group(1).toLowerCase().charAt(0);
            jsonName.setCharAt(m.start(), replacement);
            jsonName.insert(m.start(), '-');
        }

        return jsonName.toString();
    }

    static String toAttributeName(String jsonName) {
        StringBuilder attribute = new StringBuilder(jsonName);
        Pattern p = Pattern.compile("-(.)");
        Matcher m = p.matcher(attribute);

        while (m.find()) {
            char replacement = m.group(1).toUpperCase().charAt(0);
            attribute.deleteCharAt(m.start());
            attribute.setCharAt(m.start(), replacement);
        }

        return attribute.toString();
    }

    @GET
    public Response index() {
        JsonArrayBuilder errors = null;
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();

        CriteriaBuilder builder = persistenceContext.getCriteriaBuilder();
        final CriteriaQuery<Object[]> query = builder.createQuery(Object[].class);
        Root<E> root = query.from(this.entityClass);

        Metamodel model = persistenceContext.getMetamodel();
        ManagedType<E> rootType = model.managedType(this.entityClass);
        Set<String> included = new HashSet<>();
        List<Join<?, ?>> fetched = new ArrayList<>();
        Set<String> collections = rootType.getAttributes()
                                          .stream()
                                          .filter(a -> a instanceof PluralAttribute<?, ?, ?>)
                                          .map(a -> a.getName())
                                          .collect(Collectors.toSet());
        List<String> counted = new ArrayList<>(collections);

        if (params.containsKey("include")) {
            List<String> includeParams = params.get("include");

            if (includeParams.size() > 1) {
                errors = (errors != null) ? errors : Json.createArrayBuilder();
                errors.add(Json.createObjectBuilder()
                                         .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                         .add("title", "Invalid Query Parameter")
                                         .add("detail", "Multiple `include` parameters are not supported."));
            } else {
                String includeParam = includeParams.get(0);

                for (String include : includeParam.split(",")) {
                    String attribute = toAttributeName(include);

                    if (included.contains(attribute)) {
                        errors = (errors != null) ? errors : Json.createArrayBuilder();
                        errors.add(Json.createObjectBuilder()
                                                 .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                                 .add("title", "Invalid Query Parameter")
                                                 .add("detail", "The relationshop path `" + include + "` is listed multiple times."));
                    } else {
                        included.add(attribute);
                        counted.remove(attribute);

                        try {
                            fetched.add((Join<?, ?>) root.fetch(attribute, JoinType.INNER));
                        } catch (@SuppressWarnings("unused") IllegalArgumentException e) {
                            errors = (errors != null) ? errors : Json.createArrayBuilder();
                            errors.add(Json.createObjectBuilder()
                                                     .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                                     .add("title", "Invalid Query Parameter")
                                                     .add("detail", "The resource does not have a `" + include + "` relationship path."));
                        }
                    }
                }
            }
        }

        if (errors != null) {
            JsonObject response = Json.createObjectBuilder()
                                      .add("errors", errors)
                                      .build();

            return Response.status(Status.BAD_REQUEST)
                           .entity(response)
                           .build();
        }

        List<Selection<?>> selections = new ArrayList<>(1 + counted.size());
        selections.add(root);

        for (String collection : counted) {
            selections.add(builder.countDistinct(root.join(collection, JoinType.LEFT)));
        }

        query.multiselect(selections).distinct(true);

        List<Expression<?>> grouping = new ArrayList<>(1 + fetched.size());
        grouping.add(root);
        grouping.addAll(fetched);
        query.groupBy(grouping);

        if (params.containsKey("sort")) {
            String[] sortKeys = params.getFirst("sort").split(",");
            List<Order> order = new ArrayList<>(sortKeys.length);

            for (String sortKey : sortKeys) {
                boolean descending = sortKey.startsWith("-");
                String attribute = toAttributeName(sortKey.substring(descending ? 1 : 0));
                javax.persistence.criteria.Path<Object> path = root.get(attribute);

                if (descending) {
                    order.add(builder.desc(path));
                } else {
                    order.add(builder.asc(path));
                }
            }

            query.orderBy(order);
        }

        TypedQuery<Object[]> typedQuery = persistenceContext.createQuery(query);

        if (params.containsKey("page[offset]")) {
            typedQuery.setFirstResult(Integer.parseInt(params.getFirst("page[offset]")));
        }

        if (params.containsKey("page[limit]")) {
            typedQuery.setMaxResults(Integer.parseInt(params.getFirst("page[limit]")));
        }

        final List<?> results = typedQuery.getResultList();
        //System.out.println(results.size());

        @SuppressWarnings("unchecked")
        List<E> target = results.stream()
                                .map(e -> {
                                    E entity;

                                    if (e instanceof Object[]) {
                                        Object[] selected = (Object[]) e;
                                        entity = (E) selected[0];
                                        Map<String, Long> counts = new HashMap<>(selected.length - 1);
                                        for (int i = 1; i < selected.length; i++) {
                                            counts.put(counted.get(i - 1), (Long) selected[i]);
                                        }
                                        entity.setRelationshipCounts(counts);
                                    } else {
                                        entity = (E) e;
                                    }

                                    return entity;
                                })
                                .collect(Collectors.toList());

        return ok(getSerializer().serialize(target, uriInfo));
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

        return entity;
    }

    @GET
    @Path("{id}")
    public Response read(@PathParam("id") final long id) {
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        Map<String, Object> properties = new HashMap<>(1);

        if (params.containsKey("include")) {
            Map<String, Subgraph<?>> subGraphs = new HashMap<>();
            EntityGraph<E> graph = persistenceContext.createEntityGraph(entityClass);
            for (String include : params.getFirst("include").split(",")) {
                if (include.indexOf('.') > -1) {
                    Subgraph<?> sg = subGraphs.get(include.substring(0, include.indexOf('.')));
                    if (sg == null) {
                        sg = graph.addSubgraph(include.substring(0, include.indexOf('.')));
                        subGraphs.put(include.substring(0, include.indexOf('.')), sg);
                    }
                    sg.addAttributeNodes(include.substring(include.indexOf('.') + 1));
                    continue; //FIXME
                } else {
                    try {
                        graph.addAttributeNodes(toAttributeName(include.trim()));
                    } catch (@SuppressWarnings("unused") IllegalArgumentException e) {
                        return notFound();
                    }
                }
            }
            properties.put("javax.persistence.fetchgraph", graph);
        }


        E entity = persistenceContext.find(entityClass, id, properties);

        if (entity != null) {
            return ok(getSerializer().serialize(entity, uriInfo));
        }

        return notFound();
    }

    @GET
    @Path("{id}/relationships/{relationshipName}")
    public Response readRelationship(@PathParam("id") final long id, @PathParam("relationshipName") String relationshipName) {
        EntityGraph<E> graph = persistenceContext.createEntityGraph(entityClass);

        try {
            graph.addAttributeNodes(relationshipName);
        } catch (@SuppressWarnings("unused") IllegalArgumentException e) {
            return notFound();
        }

        Map<String, Object> properties = new HashMap<>(1);
        properties.put("javax.persistence.fetchgraph", graph);

        E entity = persistenceContext.find(entityClass, id, properties);

        if (entity != null) {
            JsonObject rels = getSerializer().serializeRelationships(entity, uriInfo);
            return ok(rels.get(relationshipName));
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
