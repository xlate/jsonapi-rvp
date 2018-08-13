package io.xlate.jsonapi.rvp;

import java.util.Set;

import javax.validation.ConstraintViolation;

public interface JsonApiHandler<T> {

    String getResourceType();

    //****************

    void onCreateRequest(JsonApiContext context);

    void afterCreateValidation(JsonApiContext context, Set<ConstraintViolation<JsonApiQuery>> violations);

    void beforeCreatePersist(JsonApiContext context, T entity);

    void beforeCreateResponse(JsonApiContext context);

    //****************

    void onFetchRequest(JsonApiContext context);

    void afterFetchValidationResult(JsonApiContext context, Set<ConstraintViolation<JsonApiQuery>> violations);

    void beforeFetchResponse(JsonApiContext context);

    //****************

    void onUpdateRequest(JsonApiContext context);

    void afterUpdateValidationResult(JsonApiContext context, Set<ConstraintViolation<JsonApiQuery>> violations);

    void beforeUpdateMerge(JsonApiContext context, T entity);

    void beforeUpdateResponse(JsonApiContext context);

    //****************

    void onDeleteRequest(JsonApiContext context);

    void beforeDelete(JsonApiContext context, T entity);

    void beforeDeleteResponse(JsonApiContext context);

}
