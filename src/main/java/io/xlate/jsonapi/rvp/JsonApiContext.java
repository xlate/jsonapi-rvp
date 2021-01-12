package io.xlate.jsonapi.rvp;

import javax.json.JsonObject;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.StatusType;

public interface JsonApiContext {

    public static class Attributes {
        private Attributes() {}

        public static final String VALIDATION_GROUPS = Attributes.class.getPackageName() + ".VALIDATION_GROUPS";
    }

    String getResourceType();

    String getResourceId();

    String getRelationshipName();

    JsonApiQuery getQuery();

    JsonObject getRequestEntity();

    void setResponse(StatusType status, JsonObject entity);

    void setResponse(int status, JsonObject entity);

    Response.ResponseBuilder getResponseBuilder();

    boolean hasResponse();

    <T> T getAttribute(String name);

    void setAttribute(String name, Object value);
}
