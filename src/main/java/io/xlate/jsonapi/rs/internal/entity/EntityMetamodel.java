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
        EntityMeta meta = classMetaMap.get(entityClass);

        if (meta != null) {
            return meta;
        }

        // Deal with JPA proxy classes
        return classMetaMap.entrySet()
            .stream()
            .filter(entry -> {
                Class<Object> key = entry.getKey();

                if (!key.isAssignableFrom(entityClass)) {
                    return false;
                }

                if (!entityClass.getSuperclass().isAssignableFrom(key)) {
                    return false;
                }

                return true;
            })
            .map(entry -> entry.getValue())
            .findFirst()
            .orElseGet(() -> null);
    }

    public String getResourceType(Class<?> resourceClass) {
        if (classMetaMap.containsKey(resourceClass)) {
            return classMetaMap.get(resourceClass).getResourceType();
        }
        return resourceClass.getName();
    }
}
