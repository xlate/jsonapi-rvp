package io.xlate.jsonapi.rs.internal.boundary;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.persistence.Persistence;
import javax.persistence.PersistenceUtil;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rs.internal.entity.EntityMeta;
import io.xlate.jsonapi.rs.internal.entity.EntityMetamodel;
import io.xlate.jsonapi.rs.internal.entity.FetchParameters;

public class ResourceObjectWriter {

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

    JsonObjectBuilder topLevelBuilder() {
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

        related.putAll(meta
                       .getEntityType()
                       .getAttributes()
                       .stream()
                       .filter(a -> a.isAssociation())
                       .collect(Collectors.toMap(a -> a.getName(), a -> {
                           if (util.isLoaded(bean, a.getName())) {
                               return model.getEntityMeta(bean.getClass())
                                           .getPropertyValue(bean, a.getName());
                           } else if (!a.isCollection()) {
                               return model.getEntityMeta(bean.getClass())
                                           .getPropertyValue(bean, a.getName());
                           }
                           return a;
                       })));

        return topLevelBuilder()
                   .add("data", toJson(bean, related, new FetchParameters(), uriInfo))
                   .build();
    }

    public JsonObject toJson(Object bean, UriInfo uriInfo) {
        return toJson(bean, null, uriInfo);
    }

    public JsonObject toJson(Object bean, FetchParameters params, UriInfo uriInfo) {
        return toJson(bean, Collections.emptyMap(), params, uriInfo);
    }

    public JsonObject toJson(Object bean,
                             Map<String, Object> related,
                             FetchParameters params,
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

    public JsonObject getAttributes(FetchParameters params, Object bean) {
        JsonObjectBuilder attributes = Json.createObjectBuilder();
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        String resourceType = meta.getResourceType();
        EntityType<Object> model = meta.getEntityType();

        model.getSingularAttributes()
             .stream()
             .filter(a -> !a.isId() &&
                     a.getPersistentAttributeType() == PersistentAttributeType.BASIC &&
                     (params == null ||
                             params.includeField(resourceType, a.getName())))
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
                         } else {
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
                                       FetchParameters params,
                                       UriInfo uriInfo) {

        JsonObjectBuilder jsonRelationships = Json.createObjectBuilder();

        EntityMeta meta = model.getEntityMeta(bean.getClass());
        String resourceType = meta.getResourceType();
        String rootEntityId = getId(bean);
        boolean exclusions = false;
        int included = 0;

        for (Entry<String, Object> entry : related.entrySet()) {
            String fieldName = entry.getKey();

            if (params.includeField(resourceType, fieldName)) {
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

                if (entryValue instanceof Long) {
                    Long count = (Long) entryValue;
                    relationshipEntry.add("meta", Json.createObjectBuilder().add("count", count));
                } else if (entryValue instanceof List) {
                    JsonArrayBuilder relationshipData = Json.createArrayBuilder();
                    @SuppressWarnings("unchecked")
                    List<Object> relatedEntities = (List<Object>) entryValue;

                    for (Object relatedEntity : relatedEntities) {
                        JsonObjectBuilder relatedId = Json.createObjectBuilder();
                        relatedId.add("type", model.getResourceType(relatedEntity.getClass()));
                        relatedId.add("id", getId(relatedEntity));
                        relationshipData.add(relatedId);
                    }

                    relationshipEntry.add("data", relationshipData);
                } else if (!many && entryValue != null) {
                    Object relatedEntity = entryValue;
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

    public JsonObject getRelationshipLink(UriInfo uriInfo, Object bean, String relationshipName) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        String resourceType = meta.getResourceType();
        String id = getId(bean);

        return link(uriInfo, "self", "readRelationship", meta, resourceType, id, relationshipName);
    }

    public JsonObject link(UriInfo uriInfo, String linkName, String methodName, EntityMeta meta, Object... params) {
        UriBuilder self = uriInfo.getBaseUriBuilder();
        Class<?> resourceClass = meta.getResourceClass();
        self.path(resourceClass);
        self.path(resourceClass, methodName);

        final URI link = self.build(params);

        return Json.createObjectBuilder().add(linkName, link.toString()).build();
    }
}
