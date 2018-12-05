package io.xlate.jsonapi.rvp;

import java.util.Set;

import javax.validation.ConstraintViolation;

public interface JsonApiHandler<T> {

    boolean isHandler(String resourceType, String httpMethod);

    default void onRequest(JsonApiContext context) {
    }

    default void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
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

    default void beforeDelete(JsonApiContext context, T entity) {
    }

    default void beforeResponse(JsonApiContext context) {
    }

}
