package io.xlate.jsonapi.rs.internal.boundary;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.persistence.EntityGraph;
import javax.persistence.EntityManager;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rs.JsonApiEntity;
import io.xlate.jsonapi.rs.internal.entity.EntityMeta;
import io.xlate.jsonapi.rs.internal.entity.EntityMetamodel;
import io.xlate.jsonapi.rs.internal.entity.FetchParameters;

public class PersistenceController {

    private final EntityManager em;
    private EntityMetamodel model;
    private ResourceObjectReader reader;
    private ResourceObjectWriter writer;

    public PersistenceController(EntityManager em, EntityMetamodel model) {
        this.em = em;
        this.model = model;
        this.reader = new ResourceObjectReader(model);
        this.writer = new ResourceObjectWriter(model);
    }

    SingularAttribute<Object, ?> getId(EntityType<Object> entityType) {
        return entityType.getId(entityType.getIdType().getJavaType());
    }

    List<Order> getOrderBy(CriteriaBuilder builder, Root<Object> root, FetchParameters params) {
        List<String> sortKeys = params.getSort();

        if (!sortKeys.isEmpty()) {
            List<Order> orderBy = new ArrayList<>(params.getSort().size());

            for (String sortKey : params.getSort()) {
                boolean descending = sortKey.startsWith("-");
                String attribute = sortKey.substring(descending ? 1 : 0);
                Path<Object> path = root.get(attribute);

                if (descending) {
                    orderBy.add(builder.desc(path));
                } else {
                    orderBy.add(builder.asc(path));
                }
            }
            return orderBy;
        }
        return Collections.emptyList();
    }

    public JsonObject getRelationships(String resourceType,
                                       UriInfo uriInfo,
                                       String id,
                                       String relationshipName) {

        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        validateEntityKey(meta.getEntityType());
        Class<Object> entityClass = meta.getEntityClass();

        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Object> query = builder.createQuery();

        Root<Object> root = query.from(entityClass);
        Join<Object, Object> join = root.join(relationshipName);
        EntityType<Object> joinType = em.getMetamodel().entity(join.getModel().getBindableJavaType());

        query.select(join.get(getId(joinType)));
        query.where(builder.equal(root.get(getId(root.getModel())), id));

        TypedQuery<Object> typedQuery = em.createQuery(query);

        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add("links", writer.getRelationshipLink(uriInfo, resourceType, id, relationshipName));

        JsonArrayBuilder relationships = Json.createArrayBuilder();

        for (Object relid : typedQuery.getResultList()) {
            relationships.add(Json.createObjectBuilder()
                                  .add("type", model.getResourceType(joinType.getJavaType()))
                                  .add("id", String.valueOf(relid)));
        }

        response.add("data", relationships);
        return response.build();
    }

    public JsonObject create(String resourceType, JsonObject input, UriInfo uriInfo) {
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        validateEntityKey(meta.getEntityType());

        Class<Object> entityClass = meta.getEntityClass();
        Object entity;

        try {
            entity = entityClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new InternalServerErrorException();
        }

        JsonObject data = input.getJsonObject("data");

        reader.putAttributes(entity, data.getJsonObject("attributes"));

        if (data.containsKey("relationships")) {
            handleRelationships(data, entity, meta.getEntityType());
        }

        ((JsonApiEntity) entity).setCreated("UNKNOWN",
                                            new Timestamp(System.currentTimeMillis()));

        em.persist(entity);

        return Json.createObjectBuilder()
                   .add("data", writer.toJson(entity, uriInfo))
                   .build();
    }

    public JsonObject update(String resourceType, String id, JsonObject input, UriInfo uriInfo) {
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        EntityType<Object> rootType = meta.getEntityType();
        validateEntityKey(rootType);

        final Object entity = findObject(resourceType, id);

        if (entity == null) {
            return null;
        }

        JsonObject data = input.getJsonObject("data");

        reader.putAttributes(entity, data.getJsonObject("attributes"));

        if (data.containsKey("relationships")) {
            handleRelationships(data, entity, rootType);
        }

        ((JsonApiEntity) entity).setUpdated("UNKNOWN",
                                            new Timestamp(System.currentTimeMillis()));

        final Object updatedEntity = em.merge(entity);

        return Json.createObjectBuilder()
                .add("data", writer.toJson(updatedEntity, uriInfo))
                .build();
    }

    void handleRelationships(JsonObject data, Object entity, EntityType<Object> rootType) {
        JsonArrayBuilder errors = Json.createArrayBuilder();
        JsonObject relationships = data.getJsonObject("relationships");

        for (Entry<String, JsonValue> entry : relationships.entrySet()) {
            String name = entry.getKey();
            JsonValue value = entry.getValue();
            JsonValue relationshipData = ((JsonObject) value).get("data");
            Attribute<Object, ?> entityAttribute = rootType.getAttribute(name);

            if (relationshipData.getValueType() == ValueType.ARRAY) {
                if (!entityAttribute.isCollection()) {
                    errors.add(Json.createObjectBuilder()
                                   .add("source",
                                        Json.createObjectBuilder().add("pointer", "/data/relationships/" + name))
                                   .add("title", "Invalid relationship")
                                   .add("detail", "Relationship `" + name + "` is not a collection.")
                                   .build());
                }

                Collection<Object> replacements = new ArrayList<>();

                for (JsonValue relationship : (JsonArray) relationshipData) {
                    JsonObject relationshipObject = (JsonObject) relationship;
                    String relType = relationshipObject.getString("type");
                    String relId = relationshipObject.getString("id");
                    Object replacement = this.findObject(relType, relId);
                    if (replacement != null) {
                        replacements.add(replacement);
                    } else {
                        errors.add(Json.createObjectBuilder()
                                   .add("source",
                                        Json.createObjectBuilder().add("pointer", "/data/relationships/" + name))
                                   .add("title", "Invalid relationship")
                                   .add("detail", "The resource of type `" + relType + "` with ID `" + relId + "` cannot be found."));
                    }
                }

                reader.putRelationship(entity, name, replacements);
            } else if (relationshipData.getValueType() == ValueType.OBJECT) {
                if (entityAttribute.isCollection()) {
                    errors.add(Json.createObjectBuilder()
                                   .add("source",
                                        Json.createObjectBuilder().add("pointer", "/data/relationships/" + name))
                                   .add("title", "Invalid singular relationship")
                                   .add("detail", "Relationship `" + name + "` is a collection.")
                                   .build());
                }

                JsonObject relationshipObject = (JsonObject) relationshipData;
                String relType = relationshipObject.getString("type");
                String relId = relationshipObject.getString("id");
                Object replacement = this.findObject(relType, relId);

                if (replacement != null) {
                    reader.putRelationship(entity, name, Arrays.asList(replacement));
                } else {
                    errors.add(Json.createObjectBuilder()
                               .add("source",
                                    Json.createObjectBuilder().add("pointer", "/data/relationships/" + name))
                               .add("title", "Invalid relationship")
                               .add("detail", "The resource of type `" + relType + "` with ID `" + relId + "` cannot be found."));
                }
            }
        }

        JsonArray errorsArray = errors.build();

        if (errorsArray.size() > 0) {
            throw new JsonApiBadRequestException(errorsArray);
        }
    }

    private Object findObject(String resourceType, String id) {
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        EntityType<Object> rootType = meta.getEntityType();
        validateEntityKey(rootType);
        Class<Object> entityClass = meta.getEntityClass();
        List<Attribute<Object, ?>> fetchedAttributes;

        fetchedAttributes = rootType.getAttributes()
                                    .stream()
                                    .collect(Collectors.toList());

        EntityGraph<Object> graph = em.createEntityGraph(entityClass);
        @SuppressWarnings("unchecked")
        Attribute<Object, ?>[] attrNodes = new Attribute[fetchedAttributes.size()];
        graph.addAttributeNodes(fetchedAttributes.toArray(attrNodes));

        Map<String, Object> hints = new HashMap<>();
        hints.put("javax.persistence.fetchgraph", graph);

        final Object entity;
        Class<?> keyType = meta.getIdType();

        if (keyType != String.class) {
            Object convId;
            try {
                Method valueOf = keyType.getMethod("valueOf", String.class);
                convId = valueOf.invoke(null, id);
                entity = em.find(entityClass, convId, hints);
            } catch (Exception e) {
                throw new InternalServerErrorException(e);
            }
        } else {
            entity = em.find(entityClass, id, hints);
        }

        return entity;
    }

    public JsonObject fetch(String resourceType, String id, UriInfo uriInfo) {
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        Class<Object> entityClass = meta.getEntityClass();
        EntityType<Object> rootType = meta.getEntityType();

        validateEntityKey(rootType);

        FetchParameters params = getFetchParameters(rootType, id, uriInfo);
        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = builder.createTupleQuery();

        Root<Object> root = query.from(entityClass);
        root.alias("root");

        Set<String> counted = rootType.getPluralAttributes()
                                      .stream()
                                      .map(a -> a.getName())
                                      .filter(a -> !params.getInclude().contains(a))
                                      .collect(Collectors.toSet());

        /*
         * Select the root entity and count all non-included relationships.
         */
        List<Selection<?>> selections = new ArrayList<>(1 + counted.size());
        selections.add(root);

        for (String collection : counted) {
            Join<Object, Object> countJoin = root.join(collection, JoinType.LEFT);
            Expression<Long> count = builder.countDistinct(countJoin);
            selections.add(count.alias(collection));
        }

        query.multiselect(selections);

        if (id != null) {
            Class<?> idcls = rootType.getIdType().getJavaType();
            SingularAttribute<Object, ?> idattr = rootType.getId(idcls);
            query.where(builder.equal(root.get(idattr), id));
        }

        /*
         * Group by the root entity (supports counting relationships). TODO:
         * Check this method is supported for platforms other than MySQL.
         */
        query.groupBy(root);
        query.orderBy(getOrderBy(builder, root, params));

        TypedQuery<Tuple> typedQuery = em.createQuery(query);

        if (params.getPageOffset() != null) {
            typedQuery.setFirstResult(params.getPageOffset());
        }

        if (params.getPageLimit() != null) {
            typedQuery.setMaxResults(params.getPageLimit());
        }

        final List<Tuple> results = typedQuery.getResultList();
        final Map<Object, Map<String, List<Object>>> relationships = new HashMap<>();

        /*
         * Build empty map to hold relationships based on those requested by the
         * client.
         **/
        results.stream()
               .map(result -> result.get("root"))
               .forEach(entity -> {
                   relationships.put(writer.getId(entity),
                                     params.getInclude()
                                           .stream()
                                           .collect(Collectors.toMap(relName -> relName,
                                                                     relName -> new ArrayList<Object>())));
               });

        for (String included : params.getInclude()) {
            getIncluded(params, entityClass, relationships, included);
        }

        JsonObjectBuilder response = Json.createObjectBuilder();
        JsonArrayBuilder data = Json.createArrayBuilder();
        Map<String, Object> related = new TreeMap<String, Object>();

        for (Tuple result : results) {
            Object entity = result.get("root");
            String resultId = writer.getId(entity);

            related.clear();
            related.putAll(relationships.get(resultId));
            counted.forEach(relationship -> related.put(relationship, result.get(relationship)));
            data.add(writer.toJson(entity, related, params, uriInfo));
        }

        if (id != null) {
            JsonArray singleton = data.build();
            if (singleton.isEmpty()) {
                return null;
            } else {
                response.add("data", singleton.get(0));
            }
        } else {
            response.add("data", data);
        }

        Map<String, Set<Object>> included;
        included = relationships.entrySet()
                                .stream()
                                .flatMap(e -> e.getValue().entrySet().stream())
                                .filter(e -> !e.getValue().isEmpty())
                                .collect(HashMap<String, Set<Object>>::new,
                                         (map, entry) -> {
                                             String key = entry.getKey();
                                             List<Object> value = entry.getValue();

                                             if (!map.containsKey(key)) {
                                                 Set<Object> unique = new HashSet<>();
                                                 unique.addAll(value);
                                                 map.put(key, unique);
                                             } else {
                                                 map.get(key).addAll(value);
                                             }
                                         },
                                         (m1, m2) -> {});

        if (!included.isEmpty()) {
            JsonArrayBuilder incl = Json.createArrayBuilder();

            for (Entry<String, Set<Object>> rel : included.entrySet()) {
                for (Object e : rel.getValue()) {
                    incl.add(writer.toJson(e, params, uriInfo));
                }
            }

            response.add("included", incl);
        }

        return response.build();
    }

    void validateEntityKey(EntityType<Object> rootType) {
        if (!rootType.hasSingleIdAttribute()) {
            throw new JsonApiBadRequestException("Invalid resource",
                                                 "The requested resource type "
                                                         + "has an unsupported "
                                                         + "composite key/ID.");
        }
    }

    FetchParameters getFetchParameters(EntityType<Object> rootType, String id, UriInfo uriInfo) {
        FetchParameters fetchp = new FetchParameters();
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        JsonArrayBuilder errors = Json.createArrayBuilder();

        params.entrySet()
              .stream()
              .filter(p -> p.getKey().matches("fields\\[[^]]+?\\]"))
              .forEach(p -> {
                  Pattern fieldp = Pattern.compile("fields\\[([^]]+?)\\]");
                  Matcher fieldm = fieldp.matcher(p.getKey());
                  fieldm.find();
                  for (String fieldl : p.getValue()) {
                      for (String fieldName : fieldl.split(",")) {
                          fetchp.addField(fieldm.group(1), ResourceObjectReader.toAttributeName(fieldName));
                      }
                  }
              });

        if (params.containsKey("include")) {
            List<String> includeParams = params.get("include");
            validateSingle("include", includeParams, errors);

            String includeParam = includeParams.get(0);

            for (String include : includeParam.split(",")) {
                String attribute = ResourceObjectReader.toAttributeName(include);

                if (fetchp.getInclude().contains(attribute)) {
                    errors = (errors != null) ? errors : Json.createArrayBuilder();
                    errors.add(Json.createObjectBuilder()
                                   .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                   .add("title", "Invalid Query Parameter")
                                   .add("detail",
                                        "The relationshop path `" + include + "` is listed multiple times."));
                } else {
                    try {
                        Attribute<?, ?> attr = rootType.getAttribute(attribute);

                        if (!attr.isAssociation()) {
                            errors = (errors != null) ? errors : Json.createArrayBuilder();
                            errors.add(Json.createObjectBuilder()
                                           .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                           .add("title", "Invalid Query Parameter")
                                           .add("detail", "Attribute `" + include + "` is not a relationship."));
                        } else {
                            if (!fetchp.includeField(rootType.getJavaType().getSimpleName(), attribute)) {
                                errors = (errors != null) ? errors : Json.createArrayBuilder();
                                errors.add(Json.createObjectBuilder()
                                               .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                               .add("title", "Invalid Query Parameter")
                                               .add("detail",
                                                    "Cannot include relationshop `" + include
                                                            + "` not selected by parameter `field["
                                                            + rootType.getJavaType().getSimpleName() + "]`."));
                            }
                        }
                    } catch (@SuppressWarnings("unused") IllegalArgumentException e) {
                        errors = (errors != null) ? errors : Json.createArrayBuilder();
                        errors.add(Json.createObjectBuilder()
                                       .add("source", Json.createObjectBuilder().add("parameter", "include"))
                                       .add("title", "Invalid Query Parameter")
                                       .add("detail",
                                            "The resource does not have a `" + include + "` relationship path."));
                    }

                    fetchp.getInclude().add(attribute);
                    fetchp.getCount().remove(attribute);
                }
            }
        }

        if (params.containsKey("sort")) {
            if (id != null) {
                throw new JsonApiBadRequestException(Json.createObjectBuilder()
                                                         .add("source",
                                                              Json.createObjectBuilder().add("parameter", "sort"))
                                                         .add("title", "Invalid Query Parameter")
                                                         .add("detail", "Cannot sort a single resource, `" + id + "`")
                                                         .build());
            }

            List<String> sortParams = params.get("sort");
            validateSingle("sort", sortParams, errors);

            String sortParam = sortParams.get(0);

            for (String sort : sortParam.split(",")) {
                boolean descending = sort.startsWith("-");
                String attribute = ResourceObjectReader.toAttributeName(sort.substring(descending ? 1 : 0));

                try {
                    Attribute<?, ?> attr = rootType.getAttribute(attribute);

                    if (attr.isAssociation()) {
                        errors = (errors != null) ? errors : Json.createArrayBuilder();
                        errors.add(Json.createObjectBuilder()
                                       .add("source", Json.createObjectBuilder().add("parameter", "sort"))
                                       .add("title", "Invalid Query Parameter")
                                       .add("detail", "Sort key `" + sort + "` is not an attribute."));
                    }
                } catch (@SuppressWarnings("unused") IllegalArgumentException e) {
                    errors = (errors != null) ? errors : Json.createArrayBuilder();
                    errors.add(Json.createObjectBuilder()
                                   .add("source", Json.createObjectBuilder().add("parameter", "sort"))
                                   .add("title", "Invalid Query Parameter")
                                   .add("detail", "The resource does not have a `" + sort + "` attribute."));
                }

                fetchp.getSort().add(descending ? '-' + attribute : attribute);
            }
        }

        if (params.containsKey("page[offset]")) {
            if (id != null) {
                errors.add(Json.createObjectBuilder()
                               .add("source", Json.createObjectBuilder().add("parameter", "page[offset]"))
                               .add("title", "Invalid Query Parameter")
                               .add("detail", "Pagination invalid for single resource requests."));
            }

            List<String> pageOffsetParams = params.get("page[offset]");
            validateSingle("page[offset]", pageOffsetParams, errors);

            try {
                fetchp.setPageOffset(Integer.parseInt(pageOffsetParams.get(0)));
            } catch (@SuppressWarnings("unused") NumberFormatException e) {
                errors.add(Json.createObjectBuilder()
                               .add("source", Json.createObjectBuilder().add("parameter", "page[offset]"))
                               .add("title", "Invalid Query Parameter")
                               .add("detail", "Page offset must be an integer value."));
            }
        }

        if (params.containsKey("page[limit]")) {
            if (id != null) {
                throw new JsonApiBadRequestException(Json.createObjectBuilder()
                                                         .add("source",
                                                              Json.createObjectBuilder().add("parameter",
                                                                                             "page[limit]"))
                                                         .add("title", "Invalid Query Parameter")
                                                         .add("detail",
                                                              "Pagination invalid for single resource requests")
                                                         .build());
            }

            List<String> pageLimitParams = params.get("page[limit]");
            validateSingle("page[limit]", pageLimitParams, errors);

            try {
                fetchp.setPageLimit(Integer.parseInt(pageLimitParams.get(0)));
            } catch (@SuppressWarnings("unused") NumberFormatException e) {
                errors.add(Json.createObjectBuilder()
                               .add("source", Json.createObjectBuilder().add("parameter", "page[limit]"))
                               .add("title", "Invalid Query Parameter")
                               .add("detail", "Page limit must be an integer value."));
            }
        }

        JsonArray errorsArray = errors.build();

        if (errorsArray.size() > 0) {
            throw new JsonApiBadRequestException(errorsArray);
        }

        return fetchp;
    }

    void validateSingle(String paramName, List<String> paramValues, JsonArrayBuilder errors) {
        if (paramValues.size() > 1) {
            errors.add(Json.createObjectBuilder()
                           .add("source", Json.createObjectBuilder().add("parameter", paramName))
                           .add("title", "Invalid Query Parameter")
                           .add("detail", "Multiple `" + paramName + "` parameters are not supported."));
        }
    }

    void getIncluded(FetchParameters params,
                     Class<Object> entityClass,
                     Map<Object, Map<String, List<Object>>> relationships,
                     String attribute) {

        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = builder.createTupleQuery();

        final Root<Object> root = query.from(entityClass);
        final Join<Object, Object> join = root.join(attribute);

        final List<Selection<?>> selections = new ArrayList<>();

        final SingularAttribute<Object, ?> rootKey = getId(root.getModel());
        selections.add(root.get(rootKey).alias("rootKey"));
        selections.add(join.alias("related"));

        query.multiselect(selections);
        query.where(root.get(rootKey).in(relationships.keySet()));

        TypedQuery<Tuple> typedQuery = em.createQuery(query);

        for (Tuple result : typedQuery.getResultList()) {
            Object rootEntityKey = String.valueOf(result.get("rootKey"));
            Object relatedEntity = result.get("related");

            relationships.get(rootEntityKey)
                         .get(attribute)
                         .add(relatedEntity);
        }
    }
}
