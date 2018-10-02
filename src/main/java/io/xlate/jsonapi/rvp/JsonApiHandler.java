package io.xlate.jsonapi.rvp;

import java.util.Set;

import javax.validation.ConstraintViolation;

public interface JsonApiHandler<T> {

    boolean isHandler(String resourceType, String httpMethod);

    //****************

    void onRequest(JsonApiContext context);

    void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations);

    void beforeAdd(JsonApiContext context, T entity);

    void beforeUpdate(JsonApiContext context, T entity);

    void beforeDelete(JsonApiContext context, T entity);

    void beforeResponse(JsonApiContext context);

}
