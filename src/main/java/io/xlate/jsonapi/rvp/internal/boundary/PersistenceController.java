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
package io.xlate.jsonapi.rvp.internal.boundary;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.persistence.EntityGraph;
import javax.persistence.EntityManager;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceException;
import javax.persistence.PersistenceUnitUtil;
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
import javax.persistence.metamodel.Bindable;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import io.xlate.jsonapi.rvp.internal.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.entity.FetchParameters;

import javax.ws.rs.core.UriInfo;

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

        return writer.toJsonApiRelationships(uriInfo,
                                             resourceType,
                                             id,
                                             relationshipName,
                                             model.getResourceType(joinType.getJavaType()),
                                             typedQuery.getResultList()
                                                       .stream()
                                                       .map(relatedId -> String.valueOf(relatedId))
                                                       .collect(Collectors.toSet()));
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
            throw new InternalServerErrorException(e);
        }

        reader.fromJson(this, entity, input);

        em.persist(entity);
        em.flush();

        return writer.toJsonApiResource(entity, uriInfo);
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

        reader.fromJson(this, entity, input);

        final Object updatedEntity = em.merge(entity);
        em.flush();

        return writer.toJsonApiResource(updatedEntity, uriInfo);
    }

    public boolean delete(String resourceType, String id) {
        EntityMeta meta = model.getEntityMeta(resourceType);

        if (meta == null) {
            return false;
        }

        EntityType<Object> rootType = meta.getEntityType();
        validateEntityKey(rootType);

        final Object entity = findObject(resourceType, id);

        if (entity == null) {
            return false;
        }

        try {
            em.remove(entity);
            em.flush();
            return true;
        } catch (@SuppressWarnings("unused") PersistenceException e) {
            JsonObject response;
            response = Json.createObjectBuilder()
                           .add("errors",
                                Json.createArrayBuilder()
                                    .add(Json.createObjectBuilder()
                                             .add("title", "Unexpected error")))
                           .build();

            throw new WebApplicationException(Response.status(Status.CONFLICT).entity(response).build());
        }
    }

    Object findObject(String resourceType, String id) {
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

    public JsonObject fetch(FetchParameters params) {
        EntityMeta meta = params.getEntityMeta();
        Class<Object> entityClass = meta.getEntityClass();
        EntityType<Object> rootType = meta.getEntityType();

        validateEntityKey(rootType);

        String id = params.getId();
        UriInfo uriInfo = params.getUriInfo();

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

        JsonObjectBuilder response = writer.topLevelBuilder();
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

        // Get unique set of included objects
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
            PersistenceUnitUtil util = em.getEntityManagerFactory().getPersistenceUnitUtil();

            for (Entry<String, Set<Object>> rel : included.entrySet()) {
                for (Object e : rel.getValue()) {
                    related.clear();
                    related.putAll(model.getEntityMeta(e.getClass())
                                        .getEntityType()
                                        .getAttributes()
                                        .stream()
                                        .filter(a -> a.isAssociation())
                                        .collect(Collectors.toMap(a -> a.getName(), a -> {
                                            if (util.isLoaded(e, a.getName())) {
                                                return model.getEntityMeta(e.getClass())
                                                            .getPropertyValue(e, a.getName());
                                            } else if (!a.isCollection()) {
                                                return model.getEntityMeta(e.getClass())
                                                            .getPropertyValue(e, a.getName());
                                            }
                                            return a;
                                        })));

                    incl.add(writer.toJson(e, related, params, uriInfo));
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

    void getIncluded(FetchParameters params,
                     Class<Object> entityClass,
                     Map<Object, Map<String, List<Object>>> relationships,
                     String attribute) {

        EntityType<Object> entityType = model.getEntityMeta(entityClass).getEntityType();
        Attribute<Object, ?> includedAttribute = entityType.getAttribute(attribute);
        @SuppressWarnings("unchecked")
        Bindable<Object> bindable = (Bindable<Object>) includedAttribute;
        Class<Object> includedClass = bindable.getBindableJavaType();
        Attribute<Object, ?> inverseAttribute = inverseOf(includedAttribute);

        final CriteriaBuilder builder = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = builder.createTupleQuery();

        final Root<Object> root = query.from(includedClass);
        final Join<Object, Object> join = root.join(inverseAttribute.getName());

        final List<Attribute<Object, ?>> fetchedAttributes = new ArrayList<>();
        final EntityGraph<Object> graph = em.createEntityGraph(includedClass);

        model.getEntityMeta(bindable.getBindableJavaType())
            .getEntityType()
            .getSingularAttributes()
            .stream()
            .filter(a -> a.getBindableJavaType() != entityClass)
            .forEach(a -> fetchedAttributes.add(a));

        @SuppressWarnings("unchecked")
        Attribute<Object, ?>[] attrNodes = new Attribute[fetchedAttributes.size()];
        graph.addAttributeNodes(fetchedAttributes.toArray(attrNodes));

        final List<Selection<?>> selections = new ArrayList<>();

        //final SingularAttribute<Object, ?> rootKey = getId(root.getModel());
        selections.add(join.get(getId(entityType)).alias("rootKey"));
        selections.add(root.alias("related"));

        query.multiselect(selections);
        query.where(join.get(getId(entityType)).in(relationships.keySet()));

        TypedQuery<Tuple> typedQuery = em.createQuery(query);
        typedQuery.setHint("javax.persistence.fetchgraph", graph);

        for (Tuple result : typedQuery.getResultList()) {
            Object rootEntityKey = String.valueOf(result.get("rootKey"));
            Object relatedEntity = result.get("related");

            relationships.get(rootEntityKey)
                         .get(attribute)
                         .add(relatedEntity);
        }
    }

    Attribute<Object, ?> inverseOf(Attribute<Object, ?> attribute) {
        @SuppressWarnings("unchecked")
        Bindable<Object> bindable = (Bindable<Object>) attribute;
        String mappedBy = getMappedBy(attribute);

        Class<Object> thisType = attribute.getDeclaringType().getJavaType();
        Class<Object> otherType = bindable.getBindableJavaType();
        EntityType<Object> otherMeta = model.getEntityMeta(otherType).getEntityType();

        if (mappedBy.isEmpty()) {
            for (Attribute<Object, ?> otherAttribute : otherMeta.getAttributes()) {
                @SuppressWarnings("unchecked")
                Bindable<Object> otherBindable = (Bindable<Object>) otherAttribute;

                if (thisType.equals(otherBindable.getBindableJavaType())) {
                    String otherMappedBy = getMappedBy(otherAttribute);

                    if (otherMappedBy.equals(attribute.getName())) {
                        return otherAttribute;
                    }
                }
            }

            throw new IllegalStateException("No inverse relationship mapped for " + attribute.getName());
        } else {
            return otherMeta.getAttribute(mappedBy);
        }
    }

    String getMappedBy(Attribute<Object, ?> attribute) {
        AccessibleObject member = (AccessibleObject) attribute.getJavaMember();
        String mappedBy = "";

        switch (attribute.getPersistentAttributeType()) {
        case MANY_TO_MANY: {
            ManyToMany annotation = member.getAnnotation(ManyToMany.class);
            mappedBy = annotation.mappedBy();
            break;
        }
        case ONE_TO_MANY: {
            OneToMany annotation = member.getAnnotation(OneToMany.class);
            mappedBy = annotation.mappedBy();
            break;
        }
        case ONE_TO_ONE: {
            OneToOne annotation = member.getAnnotation(OneToOne.class);
            mappedBy = annotation.mappedBy();
            break;
        }
        default:
            break;
        }

        return mappedBy;
    }
}
