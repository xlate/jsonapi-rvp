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
package io.xlate.jsonapi.rvp.internal.persistence.boundary;

import java.lang.reflect.AccessibleObject;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.enterprise.inject.spi.CDI;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.persistence.EntityGraph;
import javax.persistence.EntityManager;
import javax.persistence.FlushModeType;
import javax.persistence.ManyToMany;
import javax.persistence.NoResultException;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Bindable;
import javax.persistence.metamodel.EntityType;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiHandler;
import io.xlate.jsonapi.rvp.JsonApiQuery;
import io.xlate.jsonapi.rvp.internal.JsonApiClientErrorException;
import io.xlate.jsonapi.rvp.internal.persistence.entity.Entity;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.rs.boundary.ResourceObjectReader;
import io.xlate.jsonapi.rvp.internal.rs.boundary.ResourceObjectWriter;
import io.xlate.jsonapi.rvp.internal.validation.boundary.TransactionalValidator;

public class PersistenceController {

    private final EntityManager em;
    private final EntityMetamodel model;
    private final ResourceObjectReader reader;
    private final ResourceObjectWriter writer;

    public PersistenceController(EntityManager em, EntityMetamodel model) {
        this.em = em;
        this.model = model;
        this.reader = new ResourceObjectReader(model);
        this.writer = new ResourceObjectWriter(model);
    }

    public static <T> java.util.function.Predicate<T> not(java.util.function.Predicate<T> t) {
        return t.negate();
    }

    List<Order> getOrderBy(CriteriaBuilder builder, Root<Object> root, JsonApiQuery params) {
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

    public JsonObject getRelationships(JsonApiContext context) {

        String resourceType = context.getResourceType();
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        validateEntityKey(meta.getEntityType());
        Class<Object> entityClass = meta.getEntityClass();

        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Object> query = builder.createQuery();

        Root<Object> root = query.from(entityClass);
        String relationshipName = context.getRelationshipName();
        Join<Object, Object> join = root.join(relationshipName);
        EntityMeta joinMeta = model.getEntityMeta(join.getModel().getBindableJavaType());

        final UriInfo uriInfo = context.getUriInfo();
        final String id = context.getResourceId();

        query.select(join.get(joinMeta.getExposedIdAttribute()));
        List<Predicate> predicates = buildPredicates(builder, root, context.getSecurity().getUserPrincipal(), meta, id);

        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(new Predicate[predicates.size()]));
        }

        TypedQuery<Object> typedQuery = em.createQuery(query);

        return writer.toJsonApiRelationships(uriInfo,
                                             resourceType,
                                             id,
                                             relationshipName,
                                             joinMeta.getResourceType(),
                                             typedQuery.getResultList()
                                                       .stream()
                                                       .map(relatedId -> String.valueOf(relatedId))
                                                       .collect(Collectors.toSet()));
    }

    @SuppressWarnings("unchecked")
    public <T> JsonObject create(JsonApiContext context, JsonApiHandler<T> handler) {
        String resourceType = context.getResourceType();
        JsonObject input = context.getRequestEntity();
        UriInfo uriInfo = context.getUriInfo();
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        validateEntityKey(meta.getEntityType());

        Class<Object> entityClass = meta.getEntityClass();
        T entity;

        try {
            entity = (T) entityClass.getConstructor().newInstance();
            em.setFlushMode(FlushModeType.COMMIT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        reader.fromJson(this, context, entity, input);

        handler.beforePersist(context, entity);

        TransactionalValidator validator = CDI.current().select(TransactionalValidator.class).get();
        Set<ConstraintViolation<T>> violations = validator.validate(context.getRequest().getMethod(), entity);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        em.persist(entity);
        em.flush();

        handler.afterPersist(context, entity);

        return writer.toJsonApiResource(new Entity(meta, entity), uriInfo);
    }

    public <T> JsonObject update(JsonApiContext context, JsonApiHandler<T> handler) {
        final String resourceType = context.getResourceType();
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        final String id = context.getResourceId();
        final JsonObject input = context.getRequestEntity();
        final UriInfo uriInfo = context.getUriInfo();

        EntityType<Object> rootType = meta.getEntityType();
        validateEntityKey(rootType);

        final T entity = findObject(context, resourceType, id);

        if (entity == null) {
            return null;
        }

        handler.beforeUpdate(context, entity);

        reader.fromJson(this, context, entity, input);

        handler.beforeMerge(context, entity);

        TransactionalValidator validator = CDI.current().select(TransactionalValidator.class).get();
        Set<ConstraintViolation<T>> violations = validator.validate(context.getRequest().getMethod(), entity);
        if (!violations.isEmpty()) {
            em.detach(entity);
            throw new ConstraintViolationException(violations);
        }

        final Object updatedEntity = em.merge(entity);
        em.flush();

        handler.afterMerge(context, entity);

        return writer.toJsonApiResource(new Entity(meta, updatedEntity), uriInfo);
    }

    public <T> boolean delete(JsonApiContext context, JsonApiHandler<T> handler) {
        String resourceType = context.getResourceType();
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return false;
        }

        EntityType<Object> rootType = meta.getEntityType();
        validateEntityKey(rootType);

        String id = context.getResourceId();

        final T entity = findObject(context, resourceType, id);

        if (entity == null) {
            return false;
        }

        handler.beforeDelete(context, entity);

        try {
            em.remove(entity);
            em.flush();
            handler.afterDelete(context, entity);
            return true;
        } catch (@SuppressWarnings("unused") PersistenceException e) {
            throw new JsonApiClientErrorException(Status.CONFLICT, "Unexpected error", null);
        }
    }

    static List<Predicate> buildPredicates(CriteriaBuilder builder,
                                           Root<?> root,
                                           Principal user,
                                           EntityMeta meta,
                                           String id) {
        List<Predicate> predicates = new ArrayList<>(2);

        if (user != null) {
            String namePath = meta.getPrincipalNamePath();
            Predicate userPredicate = buildPredicate(builder, root, namePath, user.getName());

            if (userPredicate != null) {
                predicates.add(userPredicate);
            }
        }

        if (id != null) {
            final Object key = meta.readId(id);
            predicates.add(builder.equal(root.get(meta.getExposedIdAttribute()), key));
        }

        return predicates;
    }

    static Predicate buildPredicate(CriteriaBuilder builder,
                                    Root<?> root,
                                    String path,
                                    String value) {
        Predicate p = null;

        if (path != null && path.length() > 0) {
            String[] elements = path.split("\\.");
            From<?, ?> namePath = root;

            for (int i = 0; i < elements.length; i++) {
                if (i + 1 == elements.length) {
                    p = builder.equal(namePath.get(elements[i]), value);
                } else {
                    namePath = namePath.join(elements[i]);
                    namePath.alias(elements[i]);
                }
            }
        }

        return p;
    }

    @SuppressWarnings("unchecked")
    public <T> T findObject(JsonApiContext context, String resourceType, String id) {
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return null;
        }

        EntityType<Object> rootType = meta.getEntityType();
        validateEntityKey(rootType);
        Class<T> entityClass = (Class<T>) meta.getEntityClass();
        List<Attribute<Object, ?>> fetchedAttributes;

        fetchedAttributes = rootType.getAttributes()
                                    .stream()
                                    .filter(a -> !a.isAssociation())
                                    .collect(Collectors.toList());

        EntityGraph<T> graph = em.createEntityGraph(entityClass);
        Attribute<T, ?>[] attrNodes = new Attribute[fetchedAttributes.size()];
        graph.addAttributeNodes(fetchedAttributes.toArray(attrNodes));

        /*
         * Map<String, Object> hints = new HashMap<>();
         * hints.put("javax.persistence.fetchgraph", graph);
         */

        final T entity;

        try {
            final CriteriaBuilder builder = em.getCriteriaBuilder();
            final CriteriaQuery<T> query = (CriteriaQuery<T>) builder.createQuery();
            Root<T> root = query.from(entityClass);
            query.select(root.alias("root"));
            //query.multiselect(root);

            List<Predicate> predicates = buildPredicates(builder,
                                                         root,
                                                         context.getSecurity().getUserPrincipal(),
                                                         meta,
                                                         id);

            if (!predicates.isEmpty()) {
                query.where(predicates.toArray(new Predicate[predicates.size()]));
            }

            Query q = em.createQuery(query);
            q.setHint("javax.persistence.fetchgraph", graph);

            entity = (T) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return entity;
    }

    public <T> JsonObject fetch(JsonApiContext context, JsonApiHandler<T> handler) {
        JsonApiQuery params = context.getQuery();
        final EntityMeta meta;
        final EntityMeta relatedMeta;
        final String relationshipName = context.getRelationshipName();

        if (relationshipName != null) {
            meta = model.getEntityMeta(params.getEntityMeta().getRelatedEntityClass(relationshipName));
            relatedMeta = params.getEntityMeta();
        } else {
            meta = params.getEntityMeta();
            relatedMeta = null;
        }

        Class<Object> entityClass = meta.getEntityClass();
        EntityType<Object> rootType = meta.getEntityType();

        validateEntityKey(rootType);

        String id = params.getId();
        UriInfo uriInfo = params.getUriInfo();

        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = builder.createTupleQuery();

        Root<Object> root = query.from(entityClass);
        root.alias("root");
        Join<Object, Object> relatedJoin;

        if (relationshipName != null) {
            Class<Object> owningType = relatedMeta.getEntityType().getJavaType();
            Attribute<Object, ?> attribute = relatedMeta.getEntityType().getAttribute(relationshipName);
            relatedJoin = root.join(inverseOf(owningType, attribute).getName());
        } else {
            relatedJoin = null;
        }

        /*Set<String> counted = rootType.getAttributes().stream()
                                      .filter(Attribute::isAssociation)
                                      .map(Attribute::getName)
                                      .filter(meta::isRelatedTo)
                                      .filter(not(params.getInclude()::contains))
                                      .collect(Collectors.toSet());*/

        Set<String> counted = rootType.getPluralAttributes()
                                      .stream()
                                      .map(Attribute::getName)
                                      .filter(meta::isRelatedTo)
                                      .filter(not(params.getInclude()::contains))
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

        final List<Predicate> predicates;

        if (relationshipName != null) {
            predicates = buildPredicates(builder, root, context.getSecurity().getUserPrincipal(), meta, null);
            final Object key = meta.readId(id);
            predicates.add(builder.equal(relatedJoin.get(relatedMeta.getExposedIdAttribute()), key));
        } else {
            predicates = buildPredicates(builder, root, context.getSecurity().getUserPrincipal(), meta, id);
        }

        if (!params.getFilters().isEmpty()) {
            params.getFilters()
                  .entrySet()
                  .stream()
                  .map(e -> buildPredicate(builder, root, e.getKey(), e.getValue()))
                  .filter(Objects::nonNull)
                  .forEach(predicates::add);
        }

        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(new Predicate[predicates.size()]));
        }

        /*
         * Group by the root entity (supports counting relationships).
         */
        query.groupBy(root);
        query.orderBy(getOrderBy(builder, root, params));

        TypedQuery<Tuple> typedQuery = em.createQuery(query);

        if (params.getFirstResult() != null) {
            typedQuery.setFirstResult(params.getFirstResult());
        }

        TypedQuery<Long> countQuery;

        if (params.getMaxResults() != null) {
            typedQuery.setMaxResults(params.getMaxResults());

            CriteriaQuery<Long> countBuilder = builder.createQuery(Long.class);
            Root<Object> countRoot = countBuilder.from(entityClass);
            countRoot.alias("root");

            for (String collection : counted) {
                countRoot.join(collection, JoinType.LEFT).alias(collection);
            }

            if (!predicates.isEmpty()) {
                countBuilder.where(predicates.toArray(new Predicate[predicates.size()]));
            }
            Expression<Long> count = builder.countDistinct(countRoot);
            countBuilder.select(count);
            countQuery = em.createQuery(countBuilder);
        } else {
            countQuery = null;
        }

        final List<Tuple> results = typedQuery.getResultList();
        final Map<Object, Map<String, List<Entity>>> relationships = new HashMap<>();
        final Long totalResults = countQuery != null ? countQuery.getSingleResult() : null;

        /*
         * Build empty map to hold relationships based on those requested by the
         * client.
         **/
        results.stream()
               .map(result -> result.get("root"))
               .forEach(entity -> {
                   relationships.put(meta.getIdValue(entity),
                                     params.getInclude()
                                           .stream()
                                           .collect(Collectors.toMap(relName -> relName,
                                                                     relName -> new ArrayList<Entity>())));

                   if (relationshipName != null) {
                       String relatedTo = relatedJoin.getAttribute().getName();

                       if (!params.getInclude().contains(relatedTo)) {
                           relationships.get(meta.getIdValue(entity)).put(relatedTo, new ArrayList<>());
                       }
                   }
               });

        /* Only retrieve included records if something was found. */
        if (!results.isEmpty()) {
            for (String included : params.getInclude()) {
                getIncluded(params, entityClass, relationships, included);
            }
            /*if (relationshipName != null) {
                String relatedTo = relatedJoin.getAttribute().getName();

                if (!params.getInclude().contains(relatedTo)) {
                    getIncluded(params, entityClass, relationships, relatedTo);
                }
            }*/
        }

        JsonObjectBuilder response = writer.topLevelBuilder();
        if (totalResults != null) {
            response.add("meta", Json.createObjectBuilder().add("totalResults", totalResults));
        }
        JsonArrayBuilder data = Json.createArrayBuilder();
        Map<String, Object> related = new TreeMap<>();

        for (Tuple result : results) {
            Object entity = result.get("root");
            Object resultId = meta.getIdValue(entity);

            related.clear();
            related.putAll(relationships.get(resultId));
            counted.forEach(relationship -> related.put(relationship, result.get(relationship)));
            data.add(writer.toJson(new Entity(meta, entity), related, params, uriInfo));
        }

        if (id != null && relationshipName == null) {
            JsonArray singleton = data.build();

            if (singleton.isEmpty()) {
                handler.afterFind(context, null);
                return null;
            }

            @SuppressWarnings("unchecked")
            T resultEntity = (T) results.get(0).get("root");
            handler.afterFind(context, resultEntity);

            response.add("data", singleton.get(0));
        } else {
            response.add("data", data);
        }

        // Get unique set of included objects
        Map<String, Set<Entity>> included;
        included = relationships.entrySet()
                                .stream()
                                .flatMap(e -> e.getValue().entrySet().stream())
                                .filter(e -> !e.getValue().isEmpty())
                                .collect(HashMap<String, Set<Entity>>::new,
                                         (map, entry) -> {
                                             String key = entry.getKey();
                                             List<Entity> value = entry.getValue();

                                             if (!map.containsKey(key)) {
                                                 Set<Entity> unique = new HashSet<>();
                                                 unique.addAll(value);
                                                 map.put(key, unique);
                                             } else {
                                                 map.get(key).addAll(value);
                                             }
                                         },
                                         (m1, m2) -> {});

        if (!included.isEmpty()) {
            JsonArrayBuilder incl = Json.createArrayBuilder();

            /*
             * For each of the included resources related to the primary resource type,
             * create the list of their own internal relationships and convert to JSON.
             * */
            for (Set<Entity> includedType : included.values()) {
                for (Entity includedEntity : includedType) {
                    related.clear();

                    includedEntity.getEntityMeta()
                                  .getEntityType()
                                  .getAttributes()
                                  .stream()
                                  .filter(Attribute::isAssociation)
                                  .forEach(relationship -> related.put(relationship.getName(), relationship));

                    incl.add(writer.toJson(includedEntity, related, params, uriInfo));
                }
            }

            response.add("included", incl);
        }

        return response.build();
    }

    @Deprecated
    void validateEntityKey(EntityType<Object> rootType) {
        if (!rootType.hasSingleIdAttribute()) {
            throw new JsonApiClientErrorException(Status.BAD_REQUEST,
                                                  "Invalid resource",
                                                  "The requested resource type "
                                                          + "has an unsupported "
                                                          + "composite key/ID.");
        }
    }

    void getIncluded(@SuppressWarnings("unused") JsonApiQuery params,
                     Class<Object> primaryClass,
                     Map<Object, Map<String, List<Entity>>> relationships,
                     String includedName) {

        EntityMeta primaryMeta = model.getEntityMeta(primaryClass);
        EntityType<Object> primaryType = primaryMeta.getEntityType();

        Attribute<Object, ?> includedAttribute = primaryType.getAttribute(includedName);
        @SuppressWarnings("unchecked")
        Class<Object> includedClass = ((Bindable<Object>) includedAttribute).getBindableJavaType();
        EntityMeta includedMeta = model.getEntityMeta(includedClass);
        Attribute<Object, ?> inverseAttribute = inverseOf(primaryType.getJavaType(), includedAttribute);

        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = builder.createTupleQuery();

        final Root<Object> root = query.from(includedClass);
        final Join<Object, Object> join = root.join(inverseAttribute.getName());

        final Path<?> primaryId = join.get(primaryMeta.getIdAttribute());
        final Path<?> includedId = root.get(includedMeta.getExposedIdAttribute());

        final List<Selection<?>> selections = new ArrayList<>(2 + includedMeta.getAttributes().size());

        selections.add(primaryId.alias("primaryId"));
        selections.add(includedId.alias("includedId"));
        selections.addAll(includedMeta.getAttributeNames()
                                      .stream()
                                      .map(attr -> root.get(attr).alias(attr))
                                      .collect(Collectors.toList()));

        query.multiselect(selections)
             .where(primaryId.in(relationships.keySet()));

        TypedQuery<Tuple> typedQuery = em.createQuery(query);

        for (Tuple result : typedQuery.getResultList()) {
            Object primaryIdValue = result.get("primaryId");
            Object includedIdValue = result.get("includedId");

            Map<String, Object> includedAttributes = new HashMap<>();

            result.getElements()
                  .subList(2, result.getElements().size())
                  .forEach(e -> includedAttributes.put(e.getAlias(), result.get(e)));

            relationships.get(primaryIdValue)
                         .get(includedName)
                         .add(new Entity(includedMeta, includedIdValue, includedAttributes));
        }
    }

    Attribute<Object, ?> inverseOf(Class<Object> type, Attribute<Object, ?> attribute) {
        @SuppressWarnings("unchecked")
        Bindable<Object> bindable = (Bindable<Object>) attribute;
        String mappedBy = getMappedBy(attribute);

        Class<Object> otherType = bindable.getBindableJavaType();
        EntityType<Object> otherMeta = model.getEntityMeta(otherType).getEntityType();

        if (mappedBy.isEmpty()) {
            for (Attribute<Object, ?> otherAttribute : otherMeta.getAttributes()) {
                @SuppressWarnings("unchecked")
                Bindable<Object> otherBindable = (Bindable<Object>) otherAttribute;

                boolean assignable = type.isAssignableFrom(otherBindable.getBindableJavaType());

                if (assignable) {
                    String otherMappedBy = getMappedBy(otherAttribute);

                    if (otherMappedBy.equals(attribute.getName())) {
                        return otherAttribute;
                    }
                }
            }

            throw new IllegalStateException("No inverse relationship mapped for " + attribute.getName());
        }

        return otherMeta.getAttribute(mappedBy);
    }

    String getMappedBy(Attribute<Object, ?> attribute) {
        final String mappedBy;
        AccessibleObject member = (AccessibleObject) attribute.getJavaMember();

        switch (attribute.getPersistentAttributeType()) {
        case MANY_TO_MANY:
            mappedBy = member.getAnnotation(ManyToMany.class).mappedBy();
            break;
        case ONE_TO_MANY:
            mappedBy = member.getAnnotation(OneToMany.class).mappedBy();
            break;
        case ONE_TO_ONE:
            mappedBy = member.getAnnotation(OneToOne.class).mappedBy();
            break;
        default:
            mappedBy = "";
            break;
        }

        return mappedBy;
    }
}
