package io.xlate.jsonapi.rs.internal.entity;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;

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
    private static <T> Class<T> wrap(Class<T> clazz) {
        return clazz.isPrimitive() ? (Class<T>) wrapperMap.get(clazz) : clazz;
    }

    private final Class<?> resourceClass;
    private final Class<Object> entityClass;
    private final String resourceType;
    private final BeanInfo beanInfo;
    private final Map<String, PropertyDescriptor> propertyDescriptors;
    private final EntityType<Object> entityType;

    public EntityMeta(Class<?> resourceClass,
                      Class<Object> entityClass,
                      String resourceType,
                      Metamodel model) {

        this.resourceClass = resourceClass;
        this.entityClass = entityClass;
        this.resourceType = resourceType;

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

    public Class<?> getIdType() {
        return wrap(entityType.getIdType().getJavaType());
    }

    public Class<?> getResourceClass() {
        return resourceClass;
    }

    public Class<Object> getEntityClass() {
        return entityClass;
    }

    public String getResourceType() {
        return resourceType;
    }

    public BeanInfo getBeanInfo() {
        return beanInfo;
    }

    public EntityType<Object> getEntityType() {
        return entityType;
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
