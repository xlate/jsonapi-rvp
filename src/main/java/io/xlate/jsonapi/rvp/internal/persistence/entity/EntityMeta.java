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
package io.xlate.jsonapi.rvp.internal.persistence.entity;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;

import io.xlate.jsonapi.rvp.JsonApiResourceType;

public class EntityMeta {

    private static final Map<Class<?>, Class<?>> wrapperMap = new HashMap<Class<?>, Class<?>>() {
        private static final long serialVersionUID = 1L;
        {
            put(boolean.class, Boolean.class);
            put(byte.class, Byte.class);
            put(char.class, Character.class);
            put(double.class, Double.class);
            put(float.class, Float.class);
            put(int.class, Integer.class);
            put(long.class, Long.class);
            put(short.class, Short.class);
            put(void.class, Void.class);
        }
    };

    @SuppressWarnings("unchecked")
    public static <T> Class<T> wrap(Class<T> clazz) {
        return clazz.isPrimitive() ? (Class<T>) wrapperMap.get(clazz) : clazz;
    }

    private final JsonApiResourceType<?> configuredType;
    private final Class<?> resourceClass;
    private final BeanInfo beanInfo;
    private final Map<String, PropertyDescriptor> propertyDescriptors;
    private final EntityType<?> entityType;

    public EntityMeta(Class<?> resourceClass,
                      JsonApiResourceType<?> configuredType,
                      Metamodel model) {

        this.resourceClass = resourceClass;
        this.configuredType = configuredType;

        final Class<?> entityClass = configuredType.getResourceClass();

        try {
            this.beanInfo = Introspector.getBeanInfo(entityClass);
        } catch (IntrospectionException e) {
            throw new RuntimeException(e);
        }

        this.entityType = model.entity(entityClass);

        this.propertyDescriptors = Arrays.stream(beanInfo.getPropertyDescriptors())
              .collect(Collectors.toMap(descriptor -> descriptor.getName(),
                                        descriptor -> descriptor));
    }

    @SuppressWarnings("unchecked")
    public SingularAttribute<Object, ?> getExposedIdAttribute() {
        final SingularAttribute<?, ?> attr;
        final String attributeName = configuredType.getExposedIdAttribute();

        if (attributeName != null) {
            attr = entityType.getSingularAttribute(attributeName);
        } else {
            attr = entityType.getId(entityType.getIdType().getJavaType());
        }

        return (SingularAttribute<Object, ?>) attr;
    }

    public Object readId(String value) {
        return configuredType.getIdReader().apply(value);
    }

    @SuppressWarnings("unchecked")
    public SingularAttribute<Object, ?> getIdAttribute() {
        final SingularAttribute<?, ?> attr;

        attr = entityType.getId(entityType.getIdType().getJavaType());

        return (SingularAttribute<Object, ?>) attr;
    }

    public Object getIdValue(Object bean) {
        return getPropertyValue(bean, getIdAttribute().getName());
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

    public Set<String> getRelationships() {
        return configuredType.getRelationships();
    }

    public boolean isRelatedTo(String relationshipName) {
        Set<String> relationships = getRelationships();
        return relationships.isEmpty() || relationships.contains(relationshipName);
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
            throw new RuntimeException(e);
        }
    }
}
