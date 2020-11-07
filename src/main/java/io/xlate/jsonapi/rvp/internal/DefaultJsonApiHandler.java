package io.xlate.jsonapi.rvp.internal;

import java.util.Set;
import java.util.logging.Logger;

import javax.validation.ConstraintViolation;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiHandler;

public class DefaultJsonApiHandler implements JsonApiHandler<Object> {

    private static final Logger logger = Logger.getLogger(DefaultJsonApiHandler.class.getName());

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
        logger.finest("Default onRequest called");
    }

    @Override
    public void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
        // No operation
        logger.finest("Default afterValidation called");
    }

    @Override
    public void afterFind(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default afterFind called");
    }

    @Override
    public void beforePersist(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default beforePersist called");
    }

    @Override
    public void afterPersist(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default afterPersist called");
    }

    @Override
    public void beforeUpdate(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default beforeUpdate called");
    }

    @Override
    public void afterUpdate(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default afterUpdate called");
    }

    @Override
    public void beforeMerge(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default beforeMerge called");
    }

    @Override
    public void afterMerge(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default afterMerge called");
    }

    @Override
    public void beforeDelete(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default beforeDelete called");
    }

    @Override
    public void afterDelete(JsonApiContext context, Object entity) {
        // No operation
        logger.finest("Default afterDelete called");
    }

    @Override
    public void beforeResponse(JsonApiContext context) {
        // No operation
        logger.finest("Default beforeResponse called");
    }
}
