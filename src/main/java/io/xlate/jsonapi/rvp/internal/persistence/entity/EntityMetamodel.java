package io.xlate.jsonapi.rvp.internal.persistence.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.metamodel.Metamodel;

import io.xlate.jsonapi.rvp.JsonApiResourceType;

public class EntityMetamodel {

    private final Map<Class<?>, EntityMeta> classMetaMap;
    private final Map<String, EntityMeta> typeMetaMap;

    public EntityMetamodel(Class<?> resourceClass,
            Set<JsonApiResourceType<?>> resourceTypes,
            Metamodel model) {

        classMetaMap = new HashMap<>(resourceTypes.size());
        typeMetaMap = new HashMap<>(resourceTypes.size());

        Set<Class<?>> knownTypes = resourceTypes.stream()
                                                .map(JsonApiResourceType::getResourceClass)
                                                .collect(Collectors.toSet());

        for (JsonApiResourceType<?> entry : resourceTypes) {
            EntityMeta meta = new EntityMeta(resourceClass, entry, model, knownTypes);

            typeMetaMap.put(entry.getName(), meta);
            classMetaMap.put(entry.getResourceClass(), meta);
        }
    }

    public EntityMeta getEntityMeta(String resourceType) {
        return typeMetaMap.get(resourceType);
    }

    public EntityMeta getEntityMeta(Class<?> entityClass) {
        if (!classMetaMap.containsKey(entityClass)) {
            // Deal with JPA proxy classes. Find alternate and add new cross reference
            classMetaMap.keySet()
                        .stream()
                        .filter(candidate -> candidate.isAssignableFrom(entityClass))
                        .findFirst()
                        .ifPresent(alternate -> classMetaMap.put(entityClass, classMetaMap.get(alternate)));
        }

        return classMetaMap.get(entityClass);
    }

}
