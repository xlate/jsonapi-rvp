package io.xlate.jsonapi.rvp.internal.rs.entity;

import jakarta.json.JsonObject;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.validation.boundary.ValidJsonApiRequest;

@ValidJsonApiRequest
public class JsonApiRequest {

    private final String requestMethod;
    private final EntityMetamodel model;
    private final EntityMeta meta;
    private final String id;
    private final JsonObject document;

    public JsonApiRequest(String requestMethod, EntityMetamodel model, EntityMeta meta, String id, JsonObject document) {
        super();
        this.requestMethod = requestMethod;
        this.model = model;
        this.meta = meta;
        this.id = id;
        this.document = document;
    }

    public boolean isRequestMethod(String requestMethod) {
        return this.requestMethod.equals(requestMethod);
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public EntityMetamodel getModel() {
        return model;
    }

    public EntityMeta getEntityMeta() {
        return meta;
    }

    public String getId() {
        return id;
    }

    public JsonObject getDocument() {
        return document;
    }
}
