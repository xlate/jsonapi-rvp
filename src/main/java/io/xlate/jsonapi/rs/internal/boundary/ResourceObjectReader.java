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
package io.xlate.jsonapi.rs.internal.boundary;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.Bindable;
import javax.persistence.metamodel.EntityType;
import javax.ws.rs.InternalServerErrorException;

import io.xlate.jsonapi.rs.internal.entity.EntityMeta;
import io.xlate.jsonapi.rs.internal.entity.EntityMetamodel;

public class ResourceObjectReader {

    static Pattern jsonPattern = Pattern.compile("-(.)");

    private final EntityMetamodel model;

    public static String toAttributeName(String jsonName) {
        StringBuilder attribute = new StringBuilder(jsonName);
        Matcher m = jsonPattern.matcher(attribute);

        while (m.find()) {
            char replacement = m.group(1).toUpperCase().charAt(0);
            attribute.deleteCharAt(m.start());
            attribute.setCharAt(m.start(), replacement);
        }

        return attribute.toString();
    }

    public ResourceObjectReader(EntityMetamodel model) {
        this.model = model;
    }

    public void fromJson(PersistenceController persistence, Object target, JsonObject source) {
        JsonObject data = source.getJsonObject("data");

        putAttributes(target, data.getJsonObject("attributes"));

        if (data.containsKey("relationships")) {
            handleRelationships(persistence, target, data, model.getEntityMeta(target.getClass()).getEntityType());
        }
    }

    void handleRelationships(PersistenceController persistence, Object entity, JsonObject data, EntityType<Object> rootType) {
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
                    Object replacement = persistence.findObject(relType, relId);

                    if (replacement != null) {
                        replacements.add(replacement);
                    } else {
                        errors.add(Json.createObjectBuilder()
                                       .add("source",
                                            Json.createObjectBuilder().add("pointer", "/data/relationships/" + name))
                                       .add("title", "Invalid relationship")
                                       .add("detail",
                                            "The resource of type `" + relType + "` with ID `" + relId
                                                    + "` cannot be found."));
                    }
                }

                putRelationship(entity, name, replacements);
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
                Object replacement = persistence.findObject(relType, relId);

                if (replacement != null) {
                    putRelationship(entity, name, Arrays.asList(replacement));
                } else {
                    errors.add(Json.createObjectBuilder()
                                   .add("source",
                                        Json.createObjectBuilder().add("pointer", "/data/relationships/" + name))
                                   .add("title", "Invalid relationship")
                                   .add("detail",
                                        "The resource of type `" + relType + "` with ID `" + relId
                                                + "` cannot be found."));
                }
            }
        }

        JsonArray errorsArray = errors.build();

        if (errorsArray.size() > 0) {
            throw new JsonApiBadRequestException(errorsArray);
        }
    }

    public void putAttributes(Object bean, JsonObject attributes) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());
        EntityType<Object> model = meta.getEntityType();

        attributes.entrySet()
                  .stream()
                  .forEach(attribute -> {
                      String jsonKey = attribute.getKey();
                      String fieldName = toAttributeName(jsonKey);

                      //TODO: validation
                      Attribute<Object, ?> a1 = model.getAttribute(fieldName);

                      if (a1.getPersistentAttributeType() != PersistentAttributeType.BASIC) {
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
                          } else {
                              value = null;
                          }
                      } else if (propertyType == String.class) {
                          if (jsonValueType == ValueType.NULL) {
                              value = null;
                          } else if (jsonValueType == ValueType.STRING) {
                              value = ((JsonString) jsonValue).getString();
                          } else {
                              throw new IllegalStateException("Bad conversion: " + jsonValue);
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
                              throw new IllegalStateException("Bad conversion: " + jsonValue);
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
                                      throw new IllegalStateException("Bad conversion: " + jsonValue);
                                  }
                              } else if (propertyType.isAssignableFrom(Double.class)) {
                                  value = number.doubleValue();
                              } else {
                                  throw new IllegalStateException("Bad conversion: " + jsonValue);
                              }
                          } else {
                              throw new IllegalStateException("Bad conversion: " + jsonValue);
                          }
                      } else if (propertyType.isPrimitive()) {
                          if (jsonValueType == ValueType.STRING) {
                              String jsonString = ((JsonString) jsonValue).getString();
                              try {
                                  Method valueOf = propertyType.getMethod("valueOf", String.class);
                                  value = valueOf.invoke(null, jsonString);
                              } catch (Exception e) {
                                  throw new InternalServerErrorException(e);
                              }
                          } else {
                              throw new IllegalStateException("Bad conversion: " + jsonValue);
                          }
                      } else if (OffsetDateTime.class.isAssignableFrom(propertyType)) {
                          if (jsonValueType == ValueType.STRING) {
                              String jsonString = ((JsonString) jsonValue).getString();
                              value = OffsetDateTime.parse(jsonString);
                          } else {
                              throw new IllegalStateException("Bad conversion: " + jsonValue);
                          }
                      } else if ((factory = fromInstantMethod(propertyType)) != null) {
                          if (jsonValueType == ValueType.STRING) {
                              String jsonString = ((JsonString) jsonValue).getString();
                              try {
                                  value = factory.invoke(null, toInstant(jsonString));
                              } catch (Exception e) {
                                  throw new InternalServerErrorException(e);
                              }
                          } else {
                              throw new IllegalStateException("Bad conversion: " + jsonValue);
                          }
                      } else {
                          throw new IllegalStateException("Bad conversion: " + jsonValue);
                      }

                      writeProperty(desc, bean, value);
                  });
    }

    public void putRelationship(Object bean, String relationship, Collection<Object> values) {
        EntityMeta meta = model.getEntityMeta(bean.getClass());

        PropertyDescriptor desc = meta.getPropertyDescriptor(relationship);
        Class<?> relatedType = desc.getPropertyType();

        try {
            if (Collection.class.isAssignableFrom(relatedType)) {
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
            } else {
                Object replacement = values.iterator().next();
                writeProperty(desc, bean, replacement);

                if (replacement != null) {
                    addRelated(replacement, bean);
                } else {
                    removeRelated(replacement, bean);
                }
            }
        } catch (Exception e) {
            throw new InternalServerErrorException(e);
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
        EntityType<Object> model = meta.getEntityType();

        model.getAttributes()
             .stream()
             .filter(a -> a.isAssociation())
             .forEach(a -> {
                 @SuppressWarnings("unchecked")
                 Bindable<Object> bindable = (Bindable<Object>) a;
                 Class<?> binding = bindable.getBindableJavaType();

                 if (binding.equals(related.getClass())) {
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
                 }
             });
    }

    @SuppressWarnings("unchecked")
    static <T> T readProperty(PropertyDescriptor descriptor, Object entity) {
        try {
            return (T) descriptor.getReadMethod().invoke(entity);
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            throw new InternalServerErrorException(e);
        }
    }

    static <T> void writeProperty(PropertyDescriptor descriptor, Object entity, T value) {
        try {
            descriptor.getWriteMethod().invoke(entity, value);
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            throw new InternalServerErrorException(e);
        }
    }

    static Method fromInstantMethod(Class<?> type) {
        try {
            return type.getMethod("from", Instant.class);
        } catch (@SuppressWarnings("unused") Exception e) {
            return null;
        }
    }

    static Instant toInstant(String jsonString) {
        return OffsetDateTime.parse(jsonString).toInstant();
    }
}
