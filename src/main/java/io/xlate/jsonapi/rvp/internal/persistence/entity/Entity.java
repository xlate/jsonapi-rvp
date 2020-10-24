package io.xlate.jsonapi.rvp.internal.persistence.entity;

import java.util.Map;
import java.util.Objects;

public class Entity {

    private final EntityMeta entityMeta;

    private final Object instance;

    private final Object id;
    private final Map<String, Object> attributes;

    public Entity(EntityMeta entityMeta, Object instance, Object id, Map<String, Object> attributes) {
        this.entityMeta = entityMeta;
        this.instance = instance;
        this.id = id;
        this.attributes = attributes;
    }

    public Entity(EntityMeta entityMeta, Object id, Map<String, Object> attributes) {
        this(entityMeta, null, id, attributes);
    }

    public Entity(EntityMeta entityMeta, Object instance) {
        this(entityMeta, instance, null, null);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Entity) {
            Entity other = (Entity) obj;
            return Objects.equals(getType(), other.getType()) && Objects.equals(getId(), other.getId());
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getType(), getId());
    }

    public EntityMeta getEntityMeta() {
        return entityMeta;
    }

    public String getType() {
        return entityMeta.getResourceType();
    }

    public Object getId() {
        if (instance != null) {
            return entityMeta.getExposedIdValue(instance);
        }
        return id;
    }

    public String getStringId() {
        return String.valueOf(getId());
    }

    public Object getAttribute(String name) {
        if (!entityMeta.getAttributeNames().contains(name)) {
            throw new IllegalArgumentException("No such attribute: " + name);
        }

        if (instance != null) {
            return entityMeta.getPropertyValue(instance, name);
        }

        if (attributes != null) {
            return attributes.get(name);
        }

        throw new IllegalStateException("Entity instance has not been set");
    }
}
