package io.xlate.jsonapi.rs.internal.boundary;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    static String toAttributeName(String jsonName) {
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
                                  throw new InternalServerErrorException();
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
                        removeRelated(related, bean, relatedType);
                    }
                }

                for (Object related : values) {
                    if (!current.contains(related)) {
                        current.add(related);
                        addRelated(related, bean, relatedType);
                    }
                }
            } else {
                Object replacement = values.iterator().next();
                writeProperty(desc, bean, replacement);

                if (replacement != null) {
                    addRelated(replacement, bean, relatedType);
                } else {
                    removeRelated(replacement, bean, relatedType);
                }
            }
        } catch (Exception e) {
            throw new InternalServerErrorException(e);
        }
    }

    void removeRelated(Object entity, Object related, Class<?> relatedType) {
        EntityMeta meta = model.getEntityMeta(entity.getClass());
        EntityType<Object> model = meta.getEntityType();

        model.getAttributes()
             .stream()
             .filter(a -> a.isAssociation())
             .forEach(a -> {
                 @SuppressWarnings("unchecked")
                 Bindable<Object> bindable = (Bindable<Object>) a;
                 Class<?> binding = bindable.getBindableJavaType();

                 if (binding.equals(relatedType)) {
                     PropertyDescriptor desc = meta.getPropertyDescriptor(a.getName());

                     if (a.isCollection()) {
                         Collection<Object> current = readProperty(desc, entity);

                         if (current != null && !current.contains(related)) {
                             current.add(related);
                         }
                     } else {
                         Object current = readProperty(desc, entity);

                         if (current == related) {
                             writeProperty(desc, entity, (Object[]) null);
                         }
                     }
                 }
             });
    }

    void addRelated(Object entity, Object related, Class<?> relatedType) {
        EntityMeta meta = model.getEntityMeta(entity.getClass());
        EntityType<Object> model = meta.getEntityType();

        model.getAttributes()
             .stream()
             .filter(a -> a.isAssociation())
             .forEach(a -> {
                 @SuppressWarnings("unchecked")
                 Bindable<Object> bindable = (Bindable<Object>) a;
                 Class<?> binding = bindable.getBindableJavaType();

                 if (binding.equals(relatedType)) {
                     PropertyDescriptor desc = meta.getPropertyDescriptor(a.getName());

                     if (a.isCollection()) {
                         Collection<Object> current = readProperty(desc, entity);

                         if (current != null && !current.contains(related)) {
                             current.add(related);
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
