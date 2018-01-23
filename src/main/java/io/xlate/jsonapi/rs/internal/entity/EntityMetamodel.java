package io.xlate.jsonapi.rs.internal.entity;

import java.util.HashMap;
import java.util.Map;

import javax.persistence.metamodel.Metamodel;

public class EntityMetamodel extends HashMap<String, EntityMeta> {

    private static final long serialVersionUID = 1L;

    private final Map<Class<Object>, EntityMeta> classMetaMap;

    public EntityMetamodel(Class<?> resourceClass,
                           Map<String, Class<Object>> resourceTypes,
                           Metamodel model) {

        super(resourceTypes.size());
        classMetaMap = new HashMap<>(resourceTypes.size());

        for (Entry<String, Class<Object>> entry : resourceTypes.entrySet()) {
            final Class<Object> entityClass = entry.getValue();
            final String resourceType = entry.getKey();
            EntityMeta meta = new EntityMeta(resourceClass, entityClass, resourceType, model);
            this.put(resourceType, meta);
            classMetaMap.put(entityClass, meta);
        }
    }

    public EntityMeta getEntityMeta(String resourceType) {
        return super.get(resourceType);
    }

    public EntityMeta getEntityMeta(Class<?> entityClass) {
        return classMetaMap.get(entityClass);
    }

    public String getResourceType(Class<?> resourceClass) {
        if (classMetaMap.containsKey(resourceClass)) {
            return classMetaMap.get(resourceClass).getResourceType();
        }
        return resourceClass.getName();
    }
}
