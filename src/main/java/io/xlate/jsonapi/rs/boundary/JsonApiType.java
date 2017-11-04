package io.xlate.jsonapi.rs.boundary;

import javax.ws.rs.core.MediaType;

public class JsonApiType extends MediaType {

    public final static String JSONAPI = "application/vnd.api+json";

    public final static MediaType JSONAPI_TYPE = new MediaType("application", "vnd.api+json");

}
