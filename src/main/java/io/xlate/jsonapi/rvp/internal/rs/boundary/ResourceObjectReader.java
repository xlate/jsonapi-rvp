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

import static io.xlate.jsonapi.rvp.internal.rs.boundary.JsonApiError.attributePointer;
import static io.xlate.jsonapi.rvp.internal.rs.boundary.JsonApiError.relationshipPointer;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Bindable;
import javax.persistence.metamodel.EntityType;
import javax.ws.rs.core.Response.Status;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiStatus;
import io.xlate.jsonapi.rvp.internal.JsonApiErrorException;
import io.xlate.jsonapi.rvp.internal.persistence.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;

public class ResourceObjectReader {

    private static final Logger LOGGER = Logger.getLogger(ResourceObjectReader.class.getName());
    private static final Object INVALID_VALUE = new Object();

    private final EntityMetamodel model;

    public ResourceObjectReader(EntityMetamodel model) {
        this.model = model;
    }

    public void fromJson(PersistenceController persistence, JsonApiContext context, Object target, JsonObject source) {
        JsonObject data = source.getJsonObject("data");

        JsonArrayBuilder errors = Json.createArrayBuilder();

        if (data.containsKey("attributes")) {
            readAttributes(target, data.getJsonObject("attributes"), errors);
        }

        if (data.containsKey("relationships")) {
            readRelationships(persistence,
                              context,
                              target,
                              data,
                              model.getEntityMeta(target.getClass()).getEntityType(),
                              errors);
        }

        JsonArray errorsArray = errors.build();

        if (!errorsArray.isEmpty()) {
            throw new JsonApiErrorException(JsonApiStatus.UNPROCESSABLE_ENTITY, errorsArray);
        }
    }

    void readRelationships(PersistenceController persistence,
                           JsonApiContext context,
                           Object entity,
                           JsonObject data,
                           EntityType<Object> rootType,
                           JsonArrayBuilder errors) {

        JsonObject relationships = data.getJsonObject("relationships");

        for (Entry<String, JsonValue> entry : relationships.entrySet()) {
            String fieldName = entry.getKey();
            JsonValue value = entry.getValue();
            JsonValue relationshipData = ((JsonObject) value).get("data");
            Attribute<Object, ?> entityAttribute = rootType.getAttribute(fieldName);

            if (relationshipData.getValueType() == ValueType.ARRAY) {
                if (!entityAttribute.isCollection()) {
                    var error = invalidRelationshipError("Value of `data` for relationship `" + fieldName + "` must not be an array.",
                                                         fieldName);
                    errors.add(error.toJson());
                } else {
                    readRelationshipArray(persistence, context, entity, fieldName, relationshipData.asJsonArray(), errors);
                }
            } else if (relationshipData.getValueType() == ValueType.OBJECT) {
                if (entityAttribute.isCollection()) {
                    var error = invalidRelationshipError("Value of `data` for relationship `" + fieldName + "` must be an array.",
                                                         fieldName);
                    errors.add(error.toJson());
                } else {
                    readRelationshipObject(persistence, context, entity, fieldName, relationshipData.asJsonObject(), errors);
                }
            }
        }
    }

    void readRelationshipArray(PersistenceController persistence,
                               JsonApiContext context,
                               Object entity,
                               String fieldName,
                               JsonArray relationshipData,
                               JsonArrayBuilder errors) {

        Collection<Object> replacements = new ArrayList<>();

        for (JsonValue relationship : relationshipData) {
            JsonObject relationshipObject = (JsonObject) relationship;
            String relType = relationshipObject.getString("type");
            String relId = relationshipObject.getString("id");
            Object replacement = persistence.findObject(context, relType, relId);

            if (replacement != null) {
                replacements.add(replacement);
            } else {
                var error = invalidRelationshipError("Resource of type `" + relType + "` with ID `" + relId + "` cannot be found.",
                                                     fieldName);
                errors.add(error.toJson());
            }
        }

        putRelationship(entity, fieldName, replacements);
    }

    void readRelationshipObject(PersistenceController persistence,
                                JsonApiContext context,
                                Object entity,
                                String fieldName,
                                JsonObject relationshipData,
                                JsonArrayBuilder errors) {

        String relType = relationshipData.getString("type");
        String relId = relationshipData.getString("id");
        Object replacement = persistence.findObject(context, relType, relId);

        if (replacement != null) {
            putRelationship(entity, fieldName, Arrays.asList(replacement));
        } else {
            var error = invalidRelationshipError("Resource of type `" + relType + "` with ID `" + relId + "` cannot be found.",
                                                 fieldName);
            errors.add(error.toJson());
        }
    }

    JsonApiError invalidRelationshipError(String detail, String relationshipName) {
        return new JsonApiError("Invalid relationship",
                                detail,
                                JsonApiError.Source.forPointer(relationshipPointer(relationshipName)));
    }

    void readAttributes(Object bean, JsonObject attributes, JsonArrayBuilder errors) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());

        attributes.entrySet()
                  .stream()
                  .filter(a -> validateAttributeFilter(a, meta, errors))
                  .forEach(a -> readAttribute(a, bean, meta, errors));
    }

    boolean validateAttributeFilter(Entry<String, JsonValue> attribute, EntityMeta meta, JsonArrayBuilder errors) {
        String jsonKey = attribute.getKey();
        boolean validAttribute = meta.getAttributeNames().contains(jsonKey);

        if (!validAttribute) {
            var error = invalidAttributeError("Unknown attribute: `" + jsonKey + "`.",
                                              jsonKey);
            errors.add(error.toJson());
        }

        return validAttribute;
    }

    void readAttribute(Entry<String, JsonValue> attribute, Object bean, EntityMeta meta, JsonArrayBuilder errors) {
        String jsonKey = attribute.getKey();
        JsonValue jsonValue = attribute.getValue();
        PropertyDescriptor desc = meta.getPropertyDescriptor(jsonKey);
        Class<?> propertyType = desc.getPropertyType();
        ValueType jsonValueType = jsonValue.getValueType();
        Method factory;
        Object value;

        if (jsonValueType == ValueType.NULL) {
            value = propertyType.isPrimitive() ? INVALID_VALUE : null;
        } else if (propertyType == String.class) {
            value = readAttributeAsString(jsonValue);
        } else if (Boolean.class.isAssignableFrom(propertyType)) {
            value = readAttributeAsBoolean(jsonValue);
        } else if (Number.class.isAssignableFrom(propertyType)) {
            value = readAttributeAsNumber(jsonKey, propertyType, jsonValue);
        } else if (propertyType.isPrimitive()) {
            value = readAttributeAsPrimitive(jsonKey, propertyType, jsonValue);
        } else if (OffsetDateTime.class.isAssignableFrom(propertyType)) {
            value = readAttributeAsOffsetDateTime(jsonValue);
        } else if ((factory = fromInstantMethod(propertyType)) != null) {
            value = readAttributeAsInstant(jsonKey, jsonValue, factory);
        } else if (jsonValueType == ValueType.STRING) {
            value = readAttributeFromString(jsonKey, propertyType, jsonValue);
        } else {
            LOGGER.warning(() -> "Unsupported attribute type: " + propertyType);
            value = INVALID_VALUE;
        }

        if (INVALID_VALUE != value) {
            writeProperty(desc, bean, value);
        } else {
            errors.add(invalidAttributeDataTypeError(jsonKey).toJson());
        }
    }

    Object readAttributeAsString(JsonValue jsonValue) {
        if (jsonValue.getValueType() == ValueType.STRING) {
            return ((JsonString) jsonValue).getString();
        } else {
            return INVALID_VALUE;
        }
    }

    Object readAttributeAsBoolean(JsonValue jsonValue) {
        switch (jsonValue.getValueType()) {
        case TRUE:
            return Boolean.TRUE;
        case FALSE:
            return Boolean.FALSE;
        default:
            return INVALID_VALUE;
        }
    }

    Object readAttributeAsNumber(String attributeName, Class<?> propertyType, JsonValue jsonValue) {
        final Object value;

        switch (jsonValue.getValueType()) {
        case NUMBER:
            JsonNumber number = (JsonNumber) jsonValue;

            if (propertyType.isAssignableFrom(BigDecimal.class)) {
                value = number.bigDecimalValue();
            } else if (propertyType.isAssignableFrom(BigInteger.class)) {
                value = number.bigIntegerValue();
            } else if (number.isIntegral()) {
                if (propertyType.isAssignableFrom(Long.class)) {
                    value = number.longValue();
                } else if (propertyType.isAssignableFrom(Integer.class)) {
                    value = number.intValue();
                } else if (propertyType.isAssignableFrom(Double.class)) {
                    value = number.doubleValue();
                } else {
                    value = INVALID_VALUE;
                }
            } else if (propertyType.isAssignableFrom(Double.class)) {
                value = number.doubleValue();
            } else {
                value = INVALID_VALUE;
            }
            break;
        case STRING:
            value = readAttributeFromString(attributeName, propertyType, jsonValue);
            break;
        default:
            value = INVALID_VALUE;
            break;
        }

        return value;
    }

    Object readAttributeAsPrimitive(String attributeName, Class<?> propertyType, JsonValue jsonValue) {
        if (jsonValue.getValueType() == ValueType.STRING) {
            return readAttributeFromString(attributeName, propertyType, jsonValue);
        } else {
            return INVALID_VALUE;
        }
    }

    Object readAttributeAsOffsetDateTime(JsonValue jsonValue) {
        if (jsonValue.getValueType() == ValueType.STRING) {
            String jsonString = ((JsonString) jsonValue).getString();
            return OffsetDateTime.parse(jsonString);
        } else {
            return INVALID_VALUE;
        }
    }

    Object readAttributeAsInstant(String attributeName, JsonValue jsonValue, Method fromInstant) {
        if (jsonValue.getValueType() == ValueType.STRING) {
            String jsonString = ((JsonString) jsonValue).getString();
            Instant instant;

            try {
                instant = OffsetDateTime.parse(jsonString).toInstant();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, () -> "Unable to convert attribute `" + attributeName + "` to an instant");
                return INVALID_VALUE;
            }

            try {
                return fromInstant.invoke(null, instant);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, () -> "Unable to invoke `fromInstant` for attribute `" + attributeName + "`");
                return INVALID_VALUE;
            }
        } else {
            return INVALID_VALUE;
        }
    }

    Object readAttributeFromString(String attributeName, Class<?> propertyType, JsonValue jsonValue) {
        String jsonString = ((JsonString) jsonValue).getString();
        Method factory;
        Object value;

        if ((factory = fromValueMethod(propertyType)) != null) {
            try {
                value = factory.invoke(null, jsonString);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, () -> "Unable to invoke `fromValue` for attribute `" + attributeName + "`");
                value = INVALID_VALUE;
            }
        } else if ((factory = valueOfMethod(propertyType)) != null) {
            try {
                value = factory.invoke(null, jsonString);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, () -> "Unable to invoke `valueOf` for attribute `" + attributeName + "`");
                value = INVALID_VALUE;
            }
        } else {
            LOGGER.fine(() -> "No `valueOf`/`fromValue` method found for attribute `" + attributeName + "`");
            value = INVALID_VALUE;
        }

        return value;
    }

    JsonApiError invalidAttributeDataTypeError(String attributeName) {
        return invalidAttributeError("Data type invalid", attributeName);
    }

    JsonApiError invalidAttributeError(String detail, String attributeName) {
        return new JsonApiError("Invalid attribute",
                                detail,
                                JsonApiError.Source.forPointer(attributePointer(attributeName)));
    }

    void putRelationship(Object bean, String relationship, Collection<Object> values) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());

        PropertyDescriptor desc = meta.getPropertyDescriptor(relationship);
        Class<?> relatedType = desc.getPropertyType();

        if (Collection.class.isAssignableFrom(relatedType)) {
            putPluralRelationship(bean, values, desc);
        } else {
            putSingularRelationship(bean, values, desc);
        }
    }

    void putPluralRelationship(Object bean, Collection<Object> values, PropertyDescriptor desc) {
        Collection<Object> current = readProperty(desc, bean);
        Iterator<Object> cursor = current.iterator();

        while (cursor.hasNext()) {
            Object related = cursor.next();

            if (!values.contains(related)) {
                cursor.remove();
                removeRelated(related, bean);
            }
        }

        for (Object related : values) {
            if (!current.contains(related)) {
                current.add(related);
                addRelated(related, bean);
            }
        }
    }

    void putSingularRelationship(Object bean, Collection<Object> values, PropertyDescriptor desc) {
        Object replacement = values.iterator().next();
        writeProperty(desc, bean, replacement);

        if (replacement != null) {
            addRelated(replacement, bean);
        } else {
            removeRelated(replacement, bean);
        }
    }

    void addRelated(Object entity, Object related) {
        updateRelated(entity, related, false);
    }

    void removeRelated(Object entity, Object related) {
        updateRelated(entity, related, true);
    }

    void updateRelated(Object entity, Object related, boolean remove) {
        EntityMeta meta = model.getEntityMeta(entity.getClass());

        meta.getEntityType()
            .getAttributes()
            .stream()
            .filter(Attribute::isAssociation)
            .filter(a -> ((Bindable<?>) a).getBindableJavaType().equals(related.getClass()))
            .forEach(a -> {
                if (a.isCollection()) {
                    updateRelatedCollection(entity, meta, a, related, remove);
                } else {
                    updateRelatedObject(entity, meta, a, related, remove);
                }
            });
    }

    @SuppressWarnings({ "rawtypes", "java:S3740" })
    void updateRelatedCollection(Object entity, EntityMeta meta, Attribute attr, Object related, boolean remove) {
        PropertyDescriptor desc = meta.getPropertyDescriptor(attr.getName());
        Collection<Object> current = readProperty(desc, entity);

        if (current != null) {
            if (current.contains(related)) {
                if (remove) {
                    current.remove(related);
                }
            } else {
                if (!remove) {
                    current.add(related);
                }
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "java:S3740" })
    void updateRelatedObject(Object entity, EntityMeta meta, Attribute attr, Object related, boolean remove) {
        PropertyDescriptor desc = meta.getPropertyDescriptor(attr.getName());

        if (remove) {
            Object current = readProperty(desc, entity);

            if (current == related) {
                writeProperty(desc, entity, (Object[]) null);
            }
        } else {
            writeProperty(desc, entity, related);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T readProperty(PropertyDescriptor descriptor, Object entity) {
        try {
            return (T) descriptor.getReadMethod().invoke(entity);
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            throw new JsonApiErrorException(Status.INTERNAL_SERVER_ERROR, "Server Error", "Unable to read property");
        }
    }

    static <T> void writeProperty(PropertyDescriptor descriptor, Object entity, T value) {
        try {
            descriptor.getWriteMethod().invoke(entity, value);
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            throw new JsonApiErrorException(Status.INTERNAL_SERVER_ERROR, "Server Error", "Unable to update property");
        }
    }

    static Method fromInstantMethod(Class<?> type) {
        try {
            return type.getMethod("from", Instant.class);
        } catch (Exception e) {
            LOGGER.log(Level.FINEST, () -> "Unable to get `from(Instant)` from class `" + type + "`: " + e.getMessage());
            return null;
        }
    }

    static Method fromValueMethod(Class<?> type) {
        try {
            return type.getMethod("fromValue", String.class);
        } catch (Exception e) {
            LOGGER.log(Level.FINEST, () -> "Unable to get `fromValue(String)` from class `" + type + "`: " + e.getMessage());
            return null;
        }
    }

    static Method valueOfMethod(Class<?> type) {
        try {
            return type.getMethod("valueOf", String.class);
        } catch (Exception e) {
            LOGGER.log(Level.FINEST, () -> "Unable to get `valueOf(String)` from class `" + type + "`: " + e.getMessage());
            return null;
        }
    }

}
