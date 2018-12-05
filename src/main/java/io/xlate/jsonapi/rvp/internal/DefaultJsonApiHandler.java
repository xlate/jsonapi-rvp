package io.xlate.jsonapi.rvp.internal;

import java.util.Set;

import javax.validation.ConstraintViolation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiHandler;

public class DefaultJsonApiHandler implements JsonApiHandler<Object> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultJsonApiHandler.class);

    public DefaultJsonApiHandler() {
        super();
    }

    @Override
    public boolean isHandler(String resourceType, String httpMethod) {
        return false;
    }

    @Override
    public void onRequest(JsonApiContext context) {
        // No operation
        logger.trace("Default onRequest called");
    }

    @Override
    public void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
        // No operation
        logger.trace("Default afterValidation called");
    }

    @Override
    public void beforePersist(JsonApiContext context, Object entity) {
        // No operation
        logger.trace("Default beforePersist called");
    }

    @Override
    public void beforeUpdate(JsonApiContext context, Object entity) {
        // No operation
        logger.trace("Default beforeUpdate called");
    }

    @Override
    public void beforeMerge(JsonApiContext context, Object entity) {
        // No operation
        logger.trace("Default beforeMerge called");
    }

    @Override
    public void beforeDelete(JsonApiContext context, Object entity) {
        // No operation
        logger.trace("Default beforeDelete called");
    }

    @Override
    public void beforeResponse(JsonApiContext context) {
        // No operation
        logger.trace("Default beforeResponse called");
    }
}
