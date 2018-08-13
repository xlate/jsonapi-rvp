package io.xlate.jsonapi.rvp;

import javax.json.JsonObject;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

public interface JsonApiContext {

    Request getRequest();

    UriInfo getUriInfo();

    String getResourceType();

    JsonApiQuery getQuery();

    JsonObject getRequestEntity();

    Response.ResponseBuilder getResponseBuilder();

    JsonObject getResponseEntity();

    void setResponseEntity(JsonObject entity);

    Object getAttribute(String name);

    void setAttribute(String name, Object value);
}
