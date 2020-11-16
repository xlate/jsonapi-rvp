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
import io.xlate.jsonapi.rvp.internal.JsonApiErrorException;
import io.xlate.jsonapi.rvp.internal.persistence.boundary.PersistenceController;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;

public class ResourceObjectReader {

    private final EntityMetamodel model;

    public ResourceObjectReader(EntityMetamodel model) {
        this.model = model;
    }

    public void fromJson(PersistenceController persistence, JsonApiContext context, Object target, JsonObject source) {
        JsonObject data = source.getJsonObject("data");

        JsonArrayBuilder errors = Json.createArrayBuilder();

        readAttributes(target, data.getJsonObject("attributes"), errors);

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
            throw new JsonApiErrorException(Status.BAD_REQUEST, errorsArray);
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
                  .forEach(attribute -> {
                      String jsonKey = attribute.getKey();
                      String fieldName = jsonKey;

                      if (!meta.getAttributeNames().contains(fieldName)) {
                          var error = invalidAttributeError("Unknown attribute: `" + jsonKey + "`.",
                                                            jsonKey);
                          errors.add(error.toJson());
                          return;
                      }

                      PropertyDescriptor desc = meta.getPropertyDescriptor(fieldName);
                      JsonValue jsonValue = attributes.get(jsonKey);
                      Class<?> propertyType = desc.getPropertyType();
                      ValueType jsonValueType = jsonValue.getValueType();
                      Method factory;
                      Object value;

                      if (jsonValueType == ValueType.NULL) {
                          if (propertyType.isPrimitive()) {
                              throw new IllegalStateException("Cannot convert null to primitive");
                          }
                          value = null;
                      } else if (propertyType == String.class) {
                          if (jsonValueType == ValueType.NULL) {
                              value = null;
                          } else if (jsonValueType == ValueType.STRING) {
                              value = ((JsonString) jsonValue).getString();
                          } else {
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else if (Boolean.class.isAssignableFrom(propertyType)) {
                          switch (jsonValueType) {
                          case TRUE:
                              value = Boolean.TRUE;
                              break;
                          case FALSE:
                              value = Boolean.FALSE;
                              break;
                          default:
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else if (Number.class.isAssignableFrom(propertyType)) {
                          if (jsonValueType == ValueType.NUMBER) {
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
                                      throw badConversionException(jsonKey, jsonValue);
                                  }
                              } else if (propertyType.isAssignableFrom(Double.class)) {
                                  value = number.doubleValue();
                              } else {
                                  throw badConversionException(jsonKey, jsonValue);
                              }
                          } else {
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else if (propertyType.isPrimitive()) {
                          if (jsonValueType == ValueType.STRING) {
                              String jsonString = ((JsonString) jsonValue).getString();
                              try {
                                  Method valueOf = propertyType.getMethod("valueOf", String.class);
                                  value = valueOf.invoke(null, jsonString);
                              } catch (Exception e) {
                                  throw new RuntimeException(e);
                              }
                          } else {
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else if (OffsetDateTime.class.isAssignableFrom(propertyType)) {
                          if (jsonValueType == ValueType.STRING) {
                              String jsonString = ((JsonString) jsonValue).getString();
                              value = OffsetDateTime.parse(jsonString);
                          } else {
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else if ((factory = fromInstantMethod(propertyType)) != null) {
                          if (jsonValueType == ValueType.STRING) {
                              String jsonString = ((JsonString) jsonValue).getString();
                              try {
                                  value = factory.invoke(null, toInstant(jsonString));
                              } catch (Exception e) {
                                  throw new RuntimeException(e);
                              }
                          } else {
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else if (jsonValueType == ValueType.STRING) {
                          String jsonString = ((JsonString) jsonValue).getString();

                          if ((factory = fromValueMethod(propertyType)) != null) {
                              try {
                                  value = factory.invoke(null, jsonString);
                              } catch (Exception e) {
                                  throw new RuntimeException(e);
                              }
                          } else if ((factory = valueOfMethod(propertyType)) != null) {
                              try {
                                  value = factory.invoke(null, jsonString);
                              } catch (Exception e) {
                                  throw new RuntimeException(e);
                              }
                          } else {
                              throw badConversionException(jsonKey, jsonValue);
                          }
                      } else {
                          throw badConversionException(jsonKey, jsonValue);
                      }

                      writeProperty(desc, bean, value);
                  });
    }

    JsonApiError invalidAttributeError(String detail, String attributeName) {
        return new JsonApiError("Invalid attribute",
                                detail,
                                JsonApiError.Source.forPointer(attributePointer(attributeName)));
    }

    RuntimeException badConversionException(String jsonKey, JsonValue jsonValue) {
        return new JsonApiErrorException(Status.BAD_REQUEST,
                                         "Invalid data binding",
                                         "Unable to map attribute `" + jsonKey + "` with value `" + jsonValue + "`");
    }

    public void putRelationship(Object bean, String relationship, Collection<Object> values) {
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
                PropertyDescriptor desc = meta.getPropertyDescriptor(a.getName());

                if (a.isCollection()) {
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
                } else {
                    if (remove) {
                        Object current = readProperty(desc, entity);

                        if (current == related) {
                            writeProperty(desc, entity, (Object[]) null);
                        }
                    } else {
                        writeProperty(desc, entity, related);
                    }
                }
            });
    }

    @SuppressWarnings("unchecked")
    static <T> T readProperty(PropertyDescriptor descriptor, Object entity) {
        try {
            return (T) descriptor.getReadMethod().invoke(entity);
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static <T> void writeProperty(PropertyDescriptor descriptor, Object entity, T value) {
        try {
            descriptor.getWriteMethod().invoke(entity, value);
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static Method fromInstantMethod(Class<?> type) {
        try {
            return type.getMethod("from", Instant.class);
        } catch (@SuppressWarnings("unused") Exception e) {
            return null;
        }
    }

    static Method fromValueMethod(Class<?> type) {
        try {
            return type.getMethod("fromValue", String.class);
        } catch (@SuppressWarnings("unused") Exception e) {
            return null;
        }
    }

    static Method valueOfMethod(Class<?> type) {
        try {
            return type.getMethod("valueOf", String.class);
        } catch (@SuppressWarnings("unused") Exception e) {
            return null;
        }
    }

    static Instant toInstant(String jsonString) {
        return OffsetDateTime.parse(jsonString).toInstant();
    }
}
