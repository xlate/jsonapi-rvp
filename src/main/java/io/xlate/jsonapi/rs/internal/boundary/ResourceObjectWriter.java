package io.xlate.jsonapi.rs.internal.boundary;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rs.internal.entity.EntityMeta;
import io.xlate.jsonapi.rs.internal.entity.EntityMetamodel;
import io.xlate.jsonapi.rs.internal.entity.FetchParameters;

public class ResourceObjectWriter {

    private final EntityMetamodel model;

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

    public ResourceObjectWriter(EntityMetamodel model) {
        this.model = model;
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

        PropertyDescriptor desc = meta.getPropertyDescriptor(idattr.getName());

        if (desc != null) {
            Method getter = desc.getReadMethod();

            try {
                Object id = getter.invoke(bean);
                return String.valueOf(id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        throw new InternalServerErrorException();
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
                 PropertyDescriptor desc = meta.getPropertyDescriptor(a.getName());
                 Method getter = desc.getReadMethod();

                 try {
                     Object value = getter.invoke(bean);
                     String key = toJsonName(desc.getName());

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
                     throw new InternalServerErrorException();
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
        return link(uriInfo, "self", "readRelationship", model.getEntityMeta(resourceType), resourceType, id, relationshipName);
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
