package io.xlate.jsonapi.rs.internal.entity;

import javax.json.JsonObject;

import io.xlate.jsonapi.rs.internal.boundary.ValidJsonApiRequest;

@ValidJsonApiRequest
public class JsonApiRequest {

    private final String requestMethod;
    private final EntityMeta meta;
    private final String id;
    private final JsonObject document;

    public JsonApiRequest(String requestMethod, EntityMeta meta, String id, JsonObject document) {
        super();
        this.requestMethod = requestMethod;
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
