package io.xlate.jsonapi.rvp;

import java.util.Set;

import javax.validation.ConstraintViolation;

public interface JsonApiHandler<T> {

    boolean isHandler(String resourceType, String httpMethod);

    /**
     * Called at the beginning of each request prior to validation or any
     * database operations.
     *
     * @param context
     */
    default void onRequest(JsonApiContext context) {
    }

    /**
     * Called following a validation operation.
     *
     * @param context
     * @param violations
     */
    default void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
    }

    /**
     * Called after the database has been searched for the requested
     * entity/entities.
     *
     * @param context
     * @param entity
     */
    default void afterFind(JsonApiContext context, T entity) {
    }

    /**
     * Called prior to a new entity being added to the persistence
     * context/database.
     *
     * @param context
     * @param entity
     */
    default void beforePersist(JsonApiContext context, T entity) {
    }

    /**
     * Called after a new entity has been added to the persistence
     * context/database.
     *
     * @param context
     * @param entity
     */
    default void afterPersist(JsonApiContext context, T entity) {
    }

    /**
     * Called prior to an existing entity being updated with new values
     * submitted by the client.
     *
     * @param context
     * @param entity
     */
    default void beforeUpdate(JsonApiContext context, T entity) {
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
    }

    /**
     * Called prior to an attempt to remove an existing entity from the
     * persistence context/database.
     *
     * @param context
     * @param entity
     */
    default void beforeDelete(JsonApiContext context, T entity) {
    }

    /**
     * Called after an existing entity has been removed from the persistence
     * context/database.
     *
     * @param context
     * @param entity
     */
    default void afterDelete(JsonApiContext context, T entity) {
    }

    /**
     * Called at the end of each request.
     *
     * @param context
     */
    default void beforeResponse(JsonApiContext context) {
    }

}
