package io.xlate.jsonapi.rvp.internal.rs.boundary;

import java.util.HashMap;
import java.util.Map;

import javax.json.JsonObject;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiQuery;

public class InternalContext implements JsonApiContext {

    private final Request request;
    private final UriInfo uriInfo;
    private final String resourceType;
    private final String resourceId;
    private final String relationshipName;
    private JsonApiQuery query;
    private final JsonObject requestEntity;

    private ResponseBuilder responseBuilder;
    private JsonObject responseEntity;
    private Map<String, Object> attributes = new HashMap<>();


    public InternalContext(Request request, UriInfo uriInfo, String resourceType, String id, String relationshipName, JsonObject requestEntity) {
        this.request = request;
        this.uriInfo = uriInfo;
        this.resourceType = resourceType;
        this.resourceId = id;
        this.relationshipName = relationshipName;
        this.requestEntity = requestEntity;
    }

    public InternalContext(Request request, UriInfo uriInfo, String resourceType, String id, JsonObject requestEntity) {
        this(request, uriInfo, resourceType, id, null, requestEntity);
    }

    public InternalContext(Request request, UriInfo uriInfo, String resourceType, String id, String relationshipName) {
        this(request, uriInfo, resourceType, id, relationshipName, null);
    }

    public InternalContext(Request request, UriInfo uriInfo, String resourceType, JsonObject requestEntity) {
        this(request, uriInfo, resourceType, null, null, requestEntity);
    }

    public InternalContext(Request request, UriInfo uriInfo, String resourceType, String id) {
        this(request, uriInfo, resourceType, id, null, null);
    }

    public InternalContext(Request request, UriInfo uriInfo, String resourceType) {
        this(request, uriInfo, resourceType, null, null, null);
    }

    @Override
    public Request getRequest() {
        return request;
    }

    @Override
    public UriInfo getUriInfo() {
        return uriInfo;
    }

    @Override
    public String getResourceType() {
        return resourceType;
    }

    @Override
    public String getResourceId() {
        return resourceId;
    }

    @Override
    public String getRelationshipName() {
        return relationshipName;
    }

    @Override
    public JsonApiQuery getQuery() {
        return query;
    }

    @Override
    public JsonObject getRequestEntity() {
        return requestEntity;
    }

    @Override
    public ResponseBuilder getResponseBuilder() {
        if (responseBuilder == null) {
            throw new IllegalStateException("Builder not yet initialized");
        }

        return responseBuilder;
    }

    public void setResponseBuilder(ResponseBuilder responseBuilder) {
        this.responseBuilder = responseBuilder;
    }

    @Override
    public JsonObject getResponseEntity() {
        return responseEntity;
    }

    @Override
    public void setResponseEntity(JsonObject entity) {
        this.responseEntity = entity;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        this.attributes.put(name, value);
    }

}