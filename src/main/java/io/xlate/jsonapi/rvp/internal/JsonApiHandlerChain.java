package io.xlate.jsonapi.rvp.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.validation.ConstraintViolation;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiHandler;

public class JsonApiHandlerChain implements JsonApiHandler<Object> {

    private final List<JsonApiHandler<Object>> chain = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public JsonApiHandlerChain(List<JsonApiHandler<?>> chain) {
        this.chain.addAll((Collection<? extends JsonApiHandler<Object>>) chain);
    }

    @Override
    public boolean isHandler(String resourceType, String httpMethod) {
        return false;
    }

    @Override
    public void onRequest(JsonApiContext context) {
        chain.stream().forEach(handler -> handler.onRequest(context));
    }

    @Override
    public void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
        chain.stream().forEach(handler -> handler.afterValidation(context, violations));
    }

    @Override
    public void afterFind(JsonApiContext context, Object entity) {
        chain.stream().forEach(handler -> handler.afterFind(context, entity));
    }

    @Override
    public void beforePersist(JsonApiContext context, Object entity) {
        chain.stream().forEach(handler -> handler.beforePersist(context, entity));
    }

    @Override
    public void beforeUpdate(JsonApiContext context, Object entity) {
        chain.stream().forEach(handler -> handler.beforeUpdate(context, entity));
    }

    @Override
    public void beforeMerge(JsonApiContext context, Object entity) {
        chain.stream().forEach(handler -> handler.beforeMerge(context, entity));
    }

    @Override
    public void beforeDelete(JsonApiContext context, Object entity) {
        chain.stream().forEach(handler -> handler.beforeDelete(context, entity));
    }

    @Override
    public void beforeResponse(JsonApiContext context) {
        chain.stream().forEach(handler -> handler.beforeResponse(context));
    }

}
