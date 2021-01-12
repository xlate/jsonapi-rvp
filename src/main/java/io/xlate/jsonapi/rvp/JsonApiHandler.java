package io.xlate.jsonapi.rvp;

import java.util.Set;
import java.util.logging.Logger;

import javax.validation.ConstraintViolation;

public interface JsonApiHandler<T> {

    static class LoggerHolder {
        private static final Logger logger = Logger.getLogger(JsonApiHandler.class.getName()); // NOSONAR
        private LoggerHolder() {}
    }

    boolean isHandler(String resourceType, String httpMethod);

    /**
     * Called at the beginning of each request prior to validation or any
     * database operations.
     *
     * @param context
     */
    default void onRequest(JsonApiContext context) {
        LoggerHolder.logger.finest("Default onRequest called");
    }

    /**
     * Called following a validation operation.
     *
     * @param context
     * @param violations
     */
    default void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
        LoggerHolder.logger.finest("Default afterValidation called");
    }

    /**
     * Called after the database has been searched for the requested
     * entity/entities.
     *
     * @param context
     * @param entity
     */
    default void afterFind(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default afterFind called");
    }

    /**
     * Called prior to an existing entity being updated with new values
     * submitted by the client.
     *
     * @param context
     * @param entity
     */
    default void beforeUpdate(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default beforeUpdate called");
    }

    /**
     * Called after an entity has been updated with values submitted by
     * the client.
     *
     * @param context
     * @param entity
     */
    default void afterUpdate(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default afterUpdate called");
    }


    /**
     * Called prior to a new entity being added to the persistence
     * context/database.
     *
     * @param context
     * @param entity
     */
    default void beforePersist(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default beforePersist called");
    }

    /**
     * Called after a new entity has been added to the persistence
     * context/database.
     *
     * @param context
     * @param entity
     */
    default void afterPersist(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default afterPersist called");
    }

    /**
     * Called prior to an existing entity being merged into the persistence
     * context. When this method is called, the entity has already been updated
     * with any changes submitted by the client.
     *
     * @param context
     * @param entity
     */
    default void beforeMerge(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default beforeMerge called");
    }

    /**
     * Called after an existing entity has been merged into the persistence
     * context. When this method is called, the entity has already been updated
     * with any changes submitted by the client.
     *
     * @param context
     * @param entity
     */
    default void afterMerge(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default afterMerge called");
    }

    /**
     * Called prior to an attempt to remove an existing entity from the
     * persistence context/database.
     *
     * @param context
     * @param entity
     */
    default void beforeDelete(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default beforeDelete called");
    }

    /**
     * Called after an existing entity has been removed from the persistence
     * context/database.
     *
     * @param context
     * @param entity
     */
    default void afterDelete(JsonApiContext context, T entity) {
        LoggerHolder.logger.finest("Default afterDelete called");
    }

    /**
     * Called at the end of each request.
     *
     * @param context
     */
    default void beforeResponse(JsonApiContext context) {
        LoggerHolder.logger.finest("Default beforeResponse called");
    }

}
