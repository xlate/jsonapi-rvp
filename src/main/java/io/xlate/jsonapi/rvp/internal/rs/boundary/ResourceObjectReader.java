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

import static io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiError.relationshipPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiStatus;
import io.xlate.jsonapi.rvp.internal.JsonApiErrorException;
import io.xlate.jsonapi.rvp.internal.persistence.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiError;

public class ResourceObjectReader {

    private static final Logger LOGGER = Logger.getLogger(ResourceObjectReader.class.getName());

    public static final Set<Class<?>> NUMBER_PRIMITIVES = Set.of(Byte.TYPE,
                                                                 Character.TYPE,
                                                                 Double.TYPE,
                                                                 Float.TYPE,
                                                                 Integer.TYPE,
                                                                 Long.TYPE,
                                                                 Short.TYPE);

    static final Map<Class<?>, Function<JsonNumber, ?>> NUMBER_MAPPERS;

    static {
        NUMBER_MAPPERS = Map.ofEntries(Map.entry(BigDecimal.class, JsonNumber::bigDecimalValue),
                                       Map.entry(BigInteger.class, JsonNumber::bigIntegerValue),
                                       //
                                       Map.entry(Byte.class, j -> Byte.valueOf((byte) j.intValue())),
                                       Map.entry(byte.class, j -> Byte.valueOf((byte) j.intValue())),
                                       //
                                       Map.entry(Short.class, j -> Short.valueOf((short) j.intValue())),
                                       Map.entry(short.class, j -> Short.valueOf((short) j.intValue())),
                                       //
                                       Map.entry(Character.class, j -> Character.valueOf((char) j.intValue())),
                                       Map.entry(char.class, j -> Character.valueOf((char) j.intValue())),
                                       //
                                       Map.entry(Integer.class, JsonNumber::intValue),
                                       Map.entry(int.class, JsonNumber::intValue),
                                       //
                                       Map.entry(Long.class, JsonNumber::longValue),
                                       Map.entry(long.class, JsonNumber::longValue),
                                       //
                                       Map.entry(Float.class, j -> Float.valueOf((float) j.doubleValue())),
                                       Map.entry(float.class, j -> Float.valueOf((float) j.doubleValue())),
                                       //
                                       Map.entry(Double.class, JsonNumber::doubleValue),
                                       Map.entry(double.class, JsonNumber::doubleValue));
    }

    private final EntityMetamodel model;

    public ResourceObjectReader(EntityMetamodel model) {
        this.model = model;
    }

    public void fromJson(PersistenceController persistence, JsonApiContext context, Object target, JsonObject source) {
        JsonObject data = source.getJsonObject("data");

        if (data.containsKey("attributes")) {
            readAttributes(target, data.getJsonObject("attributes"));
        }

        if (data.containsKey("relationships")) {
            readRelationships(persistence,
                              context,
                              target,
                              data,
                              model.getEntityMeta(target.getClass()).getEntityType());
        }
    }

    void readRelationships(PersistenceController persistence,
                           JsonApiContext context,
                           Object entity,
                           JsonObject data,
                           EntityType<Object> rootType) {

        JsonObject relationships = data.getJsonObject("relationships");
        JsonArrayBuilder errors = Json.createArrayBuilder();

        for (Entry<String, JsonValue> entry : relationships.entrySet()) {
            String fieldName = entry.getKey();
            JsonValue relationshipData = entry.getValue().asJsonObject().get("data");

            // Validation already completed in JsonApiRequestValidator
            if (rootType.getAttribute(fieldName).isCollection()) {
                readRelationshipArray(persistence, context, entity, fieldName, relationshipData.asJsonArray(), errors);
            } else {
                readRelationshipObject(persistence, context, entity, fieldName, relationshipData, errors);
            }
        }

        JsonArray errorsArray = errors.build();

        if (!errorsArray.isEmpty()) {
            throw new JsonApiErrorException(JsonApiStatus.UNPROCESSABLE_ENTITY, errorsArray);
        }
    }

    void readRelationshipArray(PersistenceController persistence,
                               JsonApiContext context,
                               Object entity,
                               String fieldName,
                               JsonArray relationshipData,
                               JsonArrayBuilder errors) {

        Collection<Object> replacements = relationshipData.stream()
                        .map(JsonValue::asJsonObject)
                        .map(entry -> findReplacement(persistence, context, entry, fieldName, errors))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        putPluralRelationship(entity, model.getEntityMeta(entity.getClass()), fieldName, replacements);
    }

    void readRelationshipObject(PersistenceController persistence,
                                JsonApiContext context,
                                Object entity,
                                String fieldName,
                                JsonValue relationshipData,
                                JsonArrayBuilder errors) {

        Object replacement;

        if (relationshipData.getValueType() == ValueType.NULL) {
            replacement = null;
        } else {
            replacement = findReplacement(persistence, context, relationshipData.asJsonObject(), fieldName, errors);

            if (replacement == null) {
                return;
            }
        }

        putSingularRelationship(entity, model.getEntityMeta(entity.getClass()), fieldName, replacement);
    }

    Object findReplacement(PersistenceController persistence, JsonApiContext context, JsonObject resourceId, String fieldName, JsonArrayBuilder errors) {
        final String type = resourceId.getString("type");
        final String id = resourceId.getString("id");
        final Object replacement = persistence.findObject(context, type, id);

        if (replacement == null) {
            var error = new JsonApiError("Invalid relationship",
                                         String.format("Resource not found => type: `%s`, id: `%s`", type, id),
                                         JsonApiError.Source.forPointer(relationshipPointer(fieldName)));

            errors.add(error.toJson());
        }

        return replacement;
    }

    void readAttributes(Object bean, JsonObject attributes) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        attributes.entrySet().forEach(a -> readAttribute(a, bean, meta));
    }

    void readAttribute(Entry<String, JsonValue> attribute, Object bean, EntityMeta meta) {
        String jsonKey = attribute.getKey();
        JsonValue jsonValue = attribute.getValue();
        Class<?> propertyType = meta.getPropertyDescriptor(jsonKey).getPropertyType();
        ValueType jsonValueType = jsonValue.getValueType();
        Object value;

        if (jsonValueType == ValueType.NULL) {
            value = null;
        } else if (propertyType == String.class) {
            value = ((JsonString) jsonValue).getString();
        } else if (classMatch(propertyType, Boolean.class, Boolean.TYPE)) {
            value = Boolean.valueOf(JsonValue.TRUE.equals(jsonValue));
        } else if (Number.class.isAssignableFrom(propertyType) || propertyType.isPrimitive()) {
            value = NUMBER_MAPPERS.getOrDefault(propertyType, JsonNumber::numberValue)
                                  .apply((JsonNumber) jsonValue);
        } else if (meta.getReaders().containsKey(jsonKey)) {
            value = meta.getReaders().get(jsonKey).apply(((JsonString) jsonValue).getString());
        } else {
            LOGGER.warning(() -> "Unsupported attribute type: " + propertyType);
            value = null;
        }

        meta.setPropertyValue(bean, jsonKey, value);
    }

    boolean classMatch(Class<?> propertyType, Class<?> wrapper, Class<?> primitive) {
        return propertyType.equals(wrapper) || primitive.equals(propertyType);
    }

    void putPluralRelationship(Object bean, EntityMeta meta, String relationshipName, Collection<Object> values) {
        Collection<Object> current = meta.getPropertyValue(bean, relationshipName);
        Iterator<Object> cursor = current.iterator();

        while (cursor.hasNext()) {
            Object related = cursor.next();

            if (!values.contains(related)) {
                cursor.remove();
                updateRelated(related, bean, RelatedModelAction.REMOVE);
            }
        }

        for (Object related : values) {
            if (!current.contains(related)) {
                current.add(related);
                updateRelated(related, bean, RelatedModelAction.ADD);
            }
        }
    }

    void putSingularRelationship(Object bean, EntityMeta meta, String relationshipName, Object replacement) {
        Object current = meta.getPropertyValue(bean, relationshipName);
        updateRelated(current, bean, RelatedModelAction.REMOVE);

        meta.setPropertyValue(bean, relationshipName, replacement);

        if (replacement != null) {
            updateRelated(replacement, bean, RelatedModelAction.ADD);
        }
    }

    void updateRelated(Object entity, Object related, RelatedModelAction action) {
        EntityMeta meta = model.getEntityMeta(entity.getClass());

        meta.getEntityType()
            .getAttributes()
            .stream()
            .filter(Attribute::isAssociation)
            .filter(a -> ((Bindable<?>) a).getBindableJavaType().equals(related.getClass()))
            .forEach(a -> {
                if (a.isCollection()) {
                    updateRelatedCollection(entity, meta, a, related, action);
                } else {
                    updateRelatedObject(entity, meta, a, related, action);
                }
            });
    }

    @SuppressWarnings({ "rawtypes", "java:S3740" })
    void updateRelatedCollection(Object entity, EntityMeta meta, Attribute attr, Object related, RelatedModelAction action) {
        Collection<Object> current = meta.getPropertyValue(entity, attr.getName());

        if (current != null) {
            if (current.contains(related)) {
                if (action == RelatedModelAction.REMOVE) {
                    current.remove(related);
                }
            } else {
                if (action == RelatedModelAction.ADD) {
                    current.add(related);
                }
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "java:S3740" })
    void updateRelatedObject(Object entity, EntityMeta meta, Attribute attr, Object related, RelatedModelAction action) {
        final String relationshipName = attr.getName();

        if (action == RelatedModelAction.REMOVE) {
            Object current = meta.getPropertyValue(entity, relationshipName);

            if (current == related) {
                meta.setPropertyValue(entity, relationshipName, null);
            }
        } else {
            meta.setPropertyValue(entity, relationshipName, related);
        }
    }

    enum RelatedModelAction {
        ADD,
        REMOVE;
    }

}
