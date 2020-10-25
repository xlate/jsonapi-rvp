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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.persistence.metamodel.Metamodel;

import io.xlate.jsonapi.rvp.JsonApiResourceType;

public class EntityMetamodel {

    private final Map<Class<?>, EntityMeta> classMetaMap;
    private final Map<String, EntityMeta> typeMetaMap;

    public EntityMetamodel(Class<?> resourceClass,
            Set<JsonApiResourceType<?>> resourceTypes,
            Metamodel model) {

        classMetaMap = new HashMap<>(resourceTypes.size());
        typeMetaMap = new HashMap<>(resourceTypes.size());

        for (JsonApiResourceType<?> entry : resourceTypes) {
            EntityMeta meta = new EntityMeta(resourceClass, entry, model);

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
                        .filter(key -> key.isAssignableFrom(entityClass)
                                || entityClass.getSuperclass().isAssignableFrom(key))
                        .findFirst()
                        .ifPresent(alternate -> classMetaMap.put(entityClass, classMetaMap.get(alternate)));
        }

        return classMetaMap.get(entityClass);
    }

    public String getResourceType(Class<?> entityClass) {
        EntityMeta meta = getEntityMeta(entityClass);

        if (meta != null) {
            return meta.getResourceType();
        }

        return entityClass.getName();
    }
}
