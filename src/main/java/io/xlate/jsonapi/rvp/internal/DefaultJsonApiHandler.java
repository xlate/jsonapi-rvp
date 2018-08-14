package io.xlate.jsonapi.rvp.internal;

import java.util.Set;

import javax.validation.ConstraintViolation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiHandler;

public class DefaultJsonApiHandler implements JsonApiHandler<Object, Object> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultJsonApiHandler.class);

    private final String resourceType;

    public DefaultJsonApiHandler(String resourceType) {
        super();
        this.resourceType = resourceType;
    }

    @Override
    public String getResourceType() {
        return resourceType;
    }

    @Override
    public void onRequest(JsonApiContext context) {
        // No operation
        logger.trace("onRequest called");
    }

    @Override
    public void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
        // No operation
        logger.trace("afterValidation called");
    }

    @Override
    public void beforePersist(JsonApiContext context, Object entity) {
        // No operation
        logger.trace("beforePersist called");
    }

    @Override
    public void beforeResponse(JsonApiContext context) {
        // No operation
        logger.trace("beforeResponse called");
    }
}
