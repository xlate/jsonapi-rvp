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
package io.xlate.jsonapi.rvp.internal.rs.boundary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.persistence.metamodel.Attribute;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiQuery;
import io.xlate.jsonapi.rvp.internal.persistence.entity.Entity;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;

public class ResourceObjectWriter {

    private static final Logger logger = Logger.getLogger(ResourceObjectWriter.class.getName());

    private final EntityMetamodel model;

    public ResourceObjectWriter(EntityMetamodel model) {
        this.model = model;
    }

    public JsonObjectBuilder topLevelBuilder() {
        JsonObjectBuilder topLevel = Json.createObjectBuilder();
        topLevel.add("jsonapi", Json.createObjectBuilder().add("version", "1.0"));
        return topLevel;
    }

    public JsonObject toJsonApiRelationships(UriInfo uriInfo,
                                             String resourceType,
                                             String id,
                                             String relationshipName,
                                             String relationshipType,
                                             Set<String> relatedIds) {

        JsonObjectBuilder response = topLevelBuilder();
        addLinks(response, () -> getRelationshipLink(uriInfo, resourceType, id, relationshipName));

        JsonArrayBuilder relationships = Json.createArrayBuilder();

        for (String relatedId : relatedIds) {
            relationships.add(Json.createObjectBuilder()
                                  .add("type", relationshipType)
                                  .add("id", relatedId));
        }

        response.add("data", relationships);
        return response.build();
    }

    public JsonObject toJsonApiResource(Entity bean, UriInfo uriInfo) {
        EntityMeta meta = bean.getEntityMeta();
        Map<String, Object> related = new TreeMap<>();

        meta.getRelationships().values().forEach(a -> {
            Object value;

            if (!a.isCollection()) {
                value = bean.getRelationship(a.getName());
            } else {
                value = a;
            }

            related.put(a.getName(), value);
        });

        return topLevelBuilder().add("data", toJson(bean, related, null, uriInfo)).build();
    }

    public JsonObject toJson(Entity bean, UriInfo uriInfo) {
        return toJson(bean, null, uriInfo);
    }

    public JsonObject toJson(Entity bean, JsonApiQuery params, UriInfo uriInfo) {
        return toJson(bean, Collections.emptyMap(), params, uriInfo);
    }

    public JsonObject toJson(Entity bean,
                             Map<String, Object> related,
                             JsonApiQuery params,
                             UriInfo uriInfo) {

        String resourceType = bean.getType();
        String id = bean.getStringId();
        JsonObjectBuilder builder = Json.createObjectBuilder();

        builder.add("type", resourceType);
        builder.add("id", id);
        builder.add("attributes", getAttributes(params, bean));

        JsonObject relationships = getRelationships(bean, related, params, uriInfo);

        if (relationships != null) {
            builder.add("relationships", relationships);
        }

        addLinks(builder, () -> getReadLink(uriInfo, resourceType, id));

        return builder.build();
    }

    public JsonObject getAttributes(JsonApiQuery params, Entity bean) {
        JsonObjectBuilder attributes = Json.createObjectBuilder();

        bean.getEntityMeta()
            .getAttributeNames()
            .stream()
            .filter(name -> params == null || params.includeField(bean.getType(), name))
            .sorted()
            .forEach(key -> {
                Object value = bean.getAttribute(key);

                if (value == null) {
                    attributes.addNull(key);
                } else if (Date.class.isAssignableFrom(value.getClass())) {
                    OffsetDateTime odt = ((Date) value).toInstant().atOffset(ZoneOffset.UTC);
                    attributes.add(key, odt.format(DateTimeFormatter.ISO_DATE_TIME));
                } else if (OffsetDateTime.class.isAssignableFrom(value.getClass())) {
                    OffsetDateTime odt = ((OffsetDateTime) value).toInstant().atOffset(ZoneOffset.UTC);
                    attributes.add(key, odt.format(DateTimeFormatter.ISO_DATE_TIME));
                } else if (Boolean.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (Boolean) value);
                } else if (BigDecimal.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (BigDecimal) value);
                } else if (BigInteger.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (BigInteger) value);
                } else if (Long.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (Long) value);
                } else if (Integer.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (Integer) value);
                } else if (Double.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (Double) value);
                } else if (Float.class.isAssignableFrom(value.getClass())) {
                    attributes.add(key, (Float) value);
                } else {
                    attributes.add(key, String.valueOf(value));
                }
            });

        return attributes.build();
    }

    JsonObject getRelationships(Entity bean,
                                Map<String, Object> related,
                                JsonApiQuery params,
                                UriInfo uriInfo) {

        JsonObjectBuilder jsonRelationships = Json.createObjectBuilder();
        EntityMeta meta = bean.getEntityMeta();
        AtomicInteger included = new AtomicInteger(0);
        AtomicInteger excluded = new AtomicInteger(0);

        related.entrySet()
               .stream()
               .filter(entry -> {
                   if (params != null && !params.includeField(bean.getType(), entry.getKey())) {
                       excluded.incrementAndGet();
                       return false;
                   }

                   return meta.isRelatedTo(entry.getKey());
               })
               .forEach(entry -> {
                   String fieldName = entry.getKey();
                   included.incrementAndGet();
                   JsonObjectBuilder relationshipEntry = Json.createObjectBuilder();

                   addLinks(relationshipEntry,
                            () -> getRelationshipLink(uriInfo, bean.getType(), bean.getStringId(), fieldName));

                   Object entryValue = entry.getValue();
                   boolean many = meta.getEntityType().getAttribute(fieldName).isCollection();

                   if (entryValue instanceof Attribute || Entity.UNFETCHED_RELATIONSHIP == entryValue) {
                       // No operation - only links were given if the relationship is an attribute
                       // or the special instance indicating nothing watch fetched
                   } else if (entryValue instanceof Long) {
                       addCountedRelationship(relationshipEntry, (Long) entryValue, many);
                   } else if (entryValue instanceof Collection) {
                       JsonValue relationshipData = getRelationshipCollection(fieldName, many, entryValue);

                       if (relationshipData != null) {
                           relationshipEntry.add("data", relationshipData);
                       }
                   }

                   jsonRelationships.add(fieldName, relationshipEntry);
               });

        if (included.get() > 0 || excluded.get() == 0) {
            return jsonRelationships.build();
        }

        return null;
    }

    void addCountedRelationship(JsonObjectBuilder relationshipEntry, Long count, boolean many) {
        if (many) {
            relationshipEntry.add("meta", Json.createObjectBuilder().add("count", count));

            if (count == 0) {
                relationshipEntry.add("data", Json.createArrayBuilder());
            }
        } else if (count == 0) {
            relationshipEntry.add("data", JsonValue.NULL);
        } else {
            relationshipEntry.add("meta", Json.createObjectBuilder().add("count", count));
        }
    }

    JsonValue getRelationshipCollection(String fieldName, boolean many, Object entryValue) {
        JsonValue relationshipData = null;
        @SuppressWarnings("unchecked")
        Collection<Entity> relatedEntities = (Collection<Entity>) entryValue;

        if (many) {
            JsonArrayBuilder relationshipArray = Json.createArrayBuilder();

            for (Entity relatedEntity : relatedEntities) {
                relationshipArray.add(getResourceIdentifier(relatedEntity));
            }

            relationshipData = relationshipArray.build();
        } else {
            final int count = relatedEntities.size();

            switch (count) {
            case 0:
                relationshipData = JsonValue.NULL;
                break;
            case 1:
                relationshipData = getResourceIdentifier(relatedEntities.iterator().next()).build();
                break;
            default:
                logger.warning(() -> String.format("Non-collection-valued relationship `%s` with %d could not be mapped.",
                                                   fieldName,
                                                   count));
                break;
            }
        }

        return relationshipData;
    }

    JsonObjectBuilder getResourceIdentifier(Entity resource) {
        JsonObjectBuilder identifier = Json.createObjectBuilder();
        identifier.add("type", resource.getType());
        identifier.add("id", resource.getStringId());
        return identifier;
    }

    private void addLinks(JsonObjectBuilder builder, Supplier<JsonObject> links) {
        builder.add("links", links.get());
    }

    private JsonObject getReadLink(UriInfo uriInfo, String resourceType, String id) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        link(builder, uriInfo, "self", "read", model.getEntityMeta(resourceType), resourceType, id);
        return builder.build();
    }

    private JsonObject getRelationshipLink(UriInfo uriInfo, String resourceType, String id, String relationshipName) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        final EntityMeta meta = model.getEntityMeta(resourceType);

        link(builder,
             uriInfo,
             "self",
             "readRelationship",
             meta,
             resourceType,
             id,
             relationshipName);

        final Class<?> relatedClass = meta.getRelatedEntityClass(relationshipName);

        if (relatedClass != null) {
            final EntityMeta relatedMeta = model.getEntityMeta(relatedClass);

            link(builder,
                 uriInfo,
                 "related",
                 "readRelated",
                 relatedMeta,
                 resourceType,
                 id,
                 relationshipName);
        }

        return builder.build();
    }

    private void link(JsonObjectBuilder builder,
                      UriInfo uriInfo,
                      String linkName,
                      String methodName,
                      EntityMeta meta,
                      Object... params) {

        UriBuilder self = uriInfo.getBaseUriBuilder();
        Class<?> resourceClass = meta.getResourceClass();
        self.path(resourceClass);
        self.path(resourceClass, methodName);

        final URI link = self.build(params);

        builder.add(linkName, link.toString());
    }
}
