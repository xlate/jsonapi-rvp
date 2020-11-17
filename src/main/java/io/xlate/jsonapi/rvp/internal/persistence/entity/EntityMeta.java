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
package io.xlate.jsonapi.rvp.internal.persistence.entity;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.PluralAttribute;
import javax.persistence.metamodel.SingularAttribute;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response.Status;

import io.xlate.jsonapi.rvp.JsonApiResourceType;
import io.xlate.jsonapi.rvp.internal.JsonApiErrorException;

@SuppressWarnings("java:S1452") // Suppress Sonar warnings regarding generic wildcards
public class EntityMeta {

    private static final Map<Class<?>, Class<?>> wrapperMap = Map.of(boolean.class,
                                                                     Boolean.class,
                                                                     byte.class,
                                                                     Byte.class,
                                                                     char.class,
                                                                     Character.class,
                                                                     double.class,
                                                                     Double.class,
                                                                     float.class,
                                                                     Float.class,
                                                                     int.class,
                                                                     Integer.class,
                                                                     long.class,
                                                                     Long.class,
                                                                     short.class,
                                                                     Short.class,
                                                                     void.class,
                                                                     Void.class);

    @SuppressWarnings("unchecked")
    public static <T> Class<T> wrap(Class<T> clazz) {
        return clazz.isPrimitive() ? (Class<T>) wrapperMap.get(clazz) : clazz;
    }

    private final JsonApiResourceType<?> configuredType;
    private final Class<?> resourceClass;
    private final BeanInfo beanInfo;
    private final Map<String, PropertyDescriptor> propertyDescriptors;

    private final EntityType<?> entityType;
    private final Set<String> methodsAllowed;

    private final Set<SingularAttribute<?, ?>> attributes;
    private final Set<String> attributeNames;

    private final Set<Attribute<?, ?>> relationships;
    private final Set<String> relationshipNames;

    public EntityMeta(Class<?> resourceClass,
            JsonApiResourceType<?> configuredType,
            Metamodel model) {

        this.resourceClass = resourceClass;
        this.configuredType = configuredType;

        final Class<?> entityClass = configuredType.getResourceClass();

        try {
            this.beanInfo = Introspector.getBeanInfo(entityClass);
        } catch (IntrospectionException e) {
            throw new IllegalStateException("Failed to obtain BeanInfo for class: " + entityClass, e);
        }

        this.entityType = model.entity(entityClass);
        this.methodsAllowed = configuredType
                .getMethods()
                .stream().map(method -> method.getAnnotation(HttpMethod.class).value())
                .collect(Collectors.toSet());

        this.attributes = entityType.getSingularAttributes()
                                    .stream()
                                    .filter(a -> !a.isId()
                                            && !a.getName().equals(configuredType.getExposedIdAttribute())
                                            && !a.isAssociation()
                                            && a.getPersistentAttributeType() == PersistentAttributeType.BASIC)
                                    .collect(Collectors.toSet());

        this.attributeNames = attributes.stream().map(Attribute::getName).collect(Collectors.toSet());

        this.relationships = entityType.getAttributes()
                                       .stream()
                                       .filter(Attribute::isAssociation)
                                       .filter(a -> this.configuredType.getRelationships().isEmpty()
                                               || this.configuredType.getRelationships().contains(a.getName()))
                                       .collect(Collectors.toSet());

        this.relationshipNames = relationships.stream().map(Attribute::getName).collect(Collectors.toSet());

        this.propertyDescriptors = Arrays.stream(beanInfo.getPropertyDescriptors())
                                         .collect(Collectors.toMap(PropertyDescriptor::getName,
                                                                   descriptor -> descriptor));
    }

    public boolean isMethodAllowed(String method) {
        return this.methodsAllowed.contains(method);
    }

    @SuppressWarnings("unchecked")
    public SingularAttribute<Object, ?> getExposedIdAttribute() {
        final SingularAttribute<?, ?> attr;
        final String attributeName = configuredType.getExposedIdAttribute();

        if (attributeName != null) {
            attr = entityType.getSingularAttribute(attributeName);
        } else {
            attr = getIdAttribute();
        }

        return (SingularAttribute<Object, ?>) attr;
    }

    public Set<SingularAttribute<?, ?>> getAttributes() {
        return attributes;
    }

    public Set<String> getAttributeNames() {
        return attributeNames;
    }

    public Set<Attribute<?, ?>> getRelationships() {
        return relationships;
    }

    public Set<String> getRelationshipNames() {
        return relationshipNames;
    }

    public Object readId(String value) {
        return configuredType.getIdReader().apply(value);
    }

    @SuppressWarnings("unchecked")
    public SingularAttribute<Object, ?> getIdAttribute() {
        Class<?> type = entityType.getIdType().getJavaType();
        return (SingularAttribute<Object, ?>) entityType.getId(type);
    }

    public Object getIdValue(Object bean) {
        return getPropertyValue(bean, getIdAttribute().getName());
    }

    public Object getExposedIdValue(Object bean) {
        return getPropertyValue(bean, getExposedIdAttribute().getName());
    }

    public Class<?> getResourceClass() {
        return resourceClass;
    }

    @SuppressWarnings("unchecked")
    public Class<Object> getEntityClass() {
        return (Class<Object>) configuredType.getResourceClass();
    }

    public String getResourceType() {
        return configuredType.getName();
    }

    @SuppressWarnings("unchecked")
    public EntityType<Object> getEntityType() {
        return (EntityType<Object>) entityType;
    }

    public boolean isRelatedTo(String relationshipName) {
        return getRelationshipNames().contains(relationshipName);
    }

    @SuppressWarnings("unchecked")
    public Class<Object> getRelatedEntityClass(String relationshipName) {
        if (isRelatedTo(relationshipName)) {
            Attribute<?, ?> attr = entityType.getAttribute(relationshipName);
            if (attr.isCollection()) {
                return (Class<Object>) ((PluralAttribute<?, ?, ?>) attr).getBindableJavaType();
            }
            return (Class<Object>) attr.getJavaType();
        }

        return null;
    }

    public String getPrincipalNamePath() {
        return configuredType.getPrincipalNamePath();
    }

    public PropertyDescriptor getPropertyDescriptor(String name) {
        PropertyDescriptor descriptor = propertyDescriptors.get(name);

        if (descriptor != null) {
            return descriptor;
        }

        throw new NoSuchElementException(name);
    }

    public Object getPropertyValue(Object bean, String propertyName) {
        PropertyDescriptor descriptor = getPropertyDescriptor(propertyName);

        try {
            return descriptor.getReadMethod().invoke(bean);
        } catch (Exception e) {
            throw new JsonApiErrorException(Status.INTERNAL_SERVER_ERROR, "Server Error", "Unable to read property");
        }
    }

    public Set<String> getUniqueTuple(String name) {
        return configuredType.getUniqueTuples().get(name);
    }
}
