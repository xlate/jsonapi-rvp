package io.xlate.jsonapi.rvp;

import java.util.Set;

import javax.validation.ConstraintViolation;

public interface JsonApiHandler<T, M> {

    String getResourceType();

    //****************

    void onRequest(JsonApiContext context);

    void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations);

    void beforePersist(JsonApiContext context, T entity);

    void beforeResponse(JsonApiContext context);

}
