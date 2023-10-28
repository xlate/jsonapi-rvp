package io.xlate.jsonapi.rvp;

import jakarta.ws.rs.core.MediaType;

public class JsonApiMediaType extends MediaType {

    public static final String APPLICATION_JSONAPI = "application/vnd.api+json";

    public static final MediaType APPLICATION_JSONAPI_TYPE = new MediaType("application", "vnd.api+json");

}
