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
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.persistence.Persistence;
import javax.persistence.PersistenceUtil;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiQuery;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;

public class ResourceObjectWriter {

    private static final Logger logger = Logger.getLogger(ResourceObjectWriter.class.getName());

    static Pattern attributePattern = Pattern.compile("([A-Z])[a-z]");

    static String toJsonName(String attributeName) {
        StringBuilder jsonName = new StringBuilder(attributeName);
        Matcher m = attributePattern.matcher(jsonName);

        while (m.find()) {
            char replacement = m.group(1).toLowerCase().charAt(0);
            jsonName.setCharAt(m.start(), replacement);
            jsonName.insert(m.start(), '-');
        }

        return jsonName.toString();
    }

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
        response.add("links", getRelationshipLink(uriInfo, resourceType, id, relationshipName));

        JsonArrayBuilder relationships = Json.createArrayBuilder();

        for (String relatedId : relatedIds) {
            relationships.add(Json.createObjectBuilder()
                                  .add("type", relationshipType)
                                  .add("id", relatedId));
        }

        response.add("data", relationships);
        return response.build();
    }

    public JsonObject toJsonApiResource(Object bean, UriInfo uriInfo) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        Map<String, Object> related = new TreeMap<String, Object>();

        PersistenceUtil util = Persistence.getPersistenceUtil();

        meta.getEntityType()
            .getAttributes()
            .stream()
            .filter(a -> a.isAssociation())
            .forEach(a -> {
                Object value;

                if (util.isLoaded(bean, a.getName())) {
                    value = meta.getPropertyValue(bean, a.getName());
                } else if (!a.isCollection()) {
                    value = meta.getPropertyValue(bean, a.getName());
                } else {
                    value = a;
                }

                related.put(a.getName(), value);
            });

        return topLevelBuilder().add("data", toJson(bean, related, null, uriInfo))
                                .build();
    }

    public JsonObject toJson(Object bean, UriInfo uriInfo) {
        return toJson(bean, null, uriInfo);
    }

    public JsonObject toJson(Object bean, JsonApiQuery params, UriInfo uriInfo) {
        return toJson(bean, Collections.emptyMap(), params, uriInfo);
    }

    public JsonObject toJson(Object bean,
                             Map<String, Object> related,
                             JsonApiQuery params,
                             UriInfo uriInfo) {

        String resourceType = model.getResourceType(bean.getClass());
        String id = getId(bean);
        JsonObjectBuilder builder = Json.createObjectBuilder();

        builder.add("type", resourceType);
        builder.add("id", id);
        builder.add("attributes", getAttributes(params, bean));

        JsonObject relationships = getRelationships(bean, related, params, uriInfo);

        if (relationships != null) {
            builder.add("relationships", relationships);
        }

        builder.add("links", getReadLink(uriInfo, resourceType, id));

        return builder.build();
    }

    public String getId(Object bean) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        EntityType<Object> model = meta.getEntityType();
        SingularAttribute<Object, ?> idattr = model.getId(model.getIdType().getJavaType());
        Object id = meta.getPropertyValue(bean, idattr.getName());
        return String.valueOf(id);
    }

    public JsonObject getAttributes(JsonApiQuery params, Object bean) {
        JsonObjectBuilder attributes = Json.createObjectBuilder();
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        String resourceType = meta.getResourceType();
        EntityType<Object> model = meta.getEntityType();

        model.getSingularAttributes()
             .stream()
             .filter(a -> !a.isId() &&
                     a.getPersistentAttributeType() == PersistentAttributeType.BASIC &&
                     (params == null || params.includeField(resourceType, a.getName())))
             .sorted((a1, a2) -> a1.getName().compareTo(a2.getName()))
             .forEach(a -> {
                 try {
                     String propertyName = a.getName();
                     Object value = meta.getPropertyValue(bean, propertyName);
                     String key = toJsonName(propertyName);

                     if (value != null) {
                         if (Date.class.isAssignableFrom(value.getClass())) {
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
                             // TODO: Things other than string?
                             attributes.add(key, String.valueOf(value));
                         }
                     } else {
                         attributes.addNull(key);
                     }
                 } catch (Exception e) {
                     throw new InternalServerErrorException(e);
                 }
             });

        return attributes.build();
    }

    public JsonObject getRelationships(Object bean,
                                       Map<String, Object> related,
                                       JsonApiQuery params,
                                       UriInfo uriInfo) {

        JsonObjectBuilder jsonRelationships = Json.createObjectBuilder();

        EntityMeta meta = model.getEntityMeta(bean.getClass());
        String resourceType = meta.getResourceType();
        String rootEntityId = getId(bean);
        boolean exclusions = false;
        int included = 0;

        for (Entry<String, Object> entry : related.entrySet()) {
            String fieldName = entry.getKey();

            if (params == null || params.includeField(resourceType, fieldName)) {
                included++;
                String relationshipName = toJsonName(fieldName);
                JsonObjectBuilder relationshipEntry = Json.createObjectBuilder();

                relationshipEntry.add("links",
                                      getRelationshipLink(uriInfo,
                                                          resourceType,
                                                          rootEntityId,
                                                          relationshipName));

                Object entryValue = entry.getValue();
                boolean many = meta.getEntityType().getAttribute(fieldName).isCollection();

                if (entryValue instanceof Attribute) {
                    // No operation
                } else if (entryValue instanceof Long) {
                    Long count = (Long) entryValue;
                    relationshipEntry.add("meta", Json.createObjectBuilder().add("count", count));
                } else if (entryValue instanceof Collection) {
                    JsonValue relationshipData;
                    @SuppressWarnings("unchecked")
                    Collection<Object> relatedEntities = (Collection<Object>) entryValue;

                    if (many) {
                        JsonArrayBuilder relationshipArray = Json.createArrayBuilder();

                        for (Object relatedEntity : relatedEntities) {
                            JsonObjectBuilder relatedId = Json.createObjectBuilder();
                            relatedId.add("type", model.getResourceType(relatedEntity.getClass()));
                            relatedId.add("id", getId(relatedEntity));
                            relationshipArray.add(relatedId);
                        }

                        relationshipData = relationshipArray.build();
                    } else {
                        final int count = relatedEntities.size();
                        if (count == 1) {
                            final Object relatedEntity;
                            relatedEntity = relatedEntities.iterator().next();
                            JsonObjectBuilder relatedId = Json.createObjectBuilder();
                            relatedId.add("type", model.getResourceType(relatedEntity.getClass()));
                            relatedId.add("id", getId(relatedEntity));
                            relationshipData = relatedId.build();
                        } else {
                            logger.warning("Non-collection-valued relationship `" + fieldName + "` with " + count
                                    + " could not be mapped.");
                            continue;
                        }
                    }

                    relationshipEntry.add("data", relationshipData);
                } else if (entryValue != null && !many) {
                    final Object relatedEntity = entryValue;
                    JsonObjectBuilder relatedId = Json.createObjectBuilder();
                    relatedId.add("type", model.getResourceType(relatedEntity.getClass()));
                    relatedId.add("id", getId(relatedEntity));
                    relationshipEntry.add("data", relatedId);
                }

                jsonRelationships.add(relationshipName, relationshipEntry);
            } else {
                exclusions = true;
            }
        }

        if (included > 0 || !exclusions) {
            return jsonRelationships.build();
        }

        return null;
    }

    public JsonObject getReadLink(UriInfo uriInfo, String resourceType, String id) {
        return link(uriInfo, "self", "read", model.getEntityMeta(resourceType), resourceType, id);
    }

    public JsonObject getRelationshipLink(UriInfo uriInfo, String resourceType, String id, String relationshipName) {
        return link(uriInfo,
                    "self",
                    "readRelationship",
                    model.getEntityMeta(resourceType),
                    resourceType,
                    id,
                    relationshipName);
    }

    /*public JsonObject getRelationshipLink(UriInfo uriInfo, Object bean, String relationshipName) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        String resourceType = meta.getResourceType();
        String id = getId(bean);

        return link(uriInfo, "self", "readRelationship", meta, resourceType, id, relationshipName);
    }*/

    public JsonObject link(UriInfo uriInfo, String linkName, String methodName, EntityMeta meta, Object... params) {
        UriBuilder self = uriInfo.getBaseUriBuilder();
        Class<?> resourceClass = meta.getResourceClass();
        self.path(resourceClass);
        self.path(resourceClass, methodName);

        final URI link = self.build(params);

        return Json.createObjectBuilder().add(linkName, link.toString()).build();
    }
}
