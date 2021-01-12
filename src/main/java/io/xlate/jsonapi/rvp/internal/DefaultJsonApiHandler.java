package io.xlate.jsonapi.rvp.internal;

import io.xlate.jsonapi.rvp.JsonApiHandler;

public class DefaultJsonApiHandler implements JsonApiHandler<Object> {

    @Override
    public boolean isHandler(String resourceType, String httpMethod) {
        return false;
    }

}
