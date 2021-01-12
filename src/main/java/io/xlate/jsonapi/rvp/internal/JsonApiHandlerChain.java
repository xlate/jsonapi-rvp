package io.xlate.jsonapi.rvp.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.validation.ConstraintViolation;

import io.xlate.jsonapi.rvp.JsonApiContext;
import io.xlate.jsonapi.rvp.JsonApiHandler;

public class JsonApiHandlerChain implements JsonApiHandler<Object> {

    private final List<JsonApiHandler<Object>> chain = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public JsonApiHandlerChain(List<JsonApiHandler<?>> chain) {
        chain.forEach(h -> this.chain.add((JsonApiHandler<Object>) h));
    }

    @Override
    public boolean isHandler(String resourceType, String httpMethod) {
        return false;
    }

    @Override
    public void onRequest(JsonApiContext context) {
        chain.forEach(handler -> handler.onRequest(context));
    }

    @Override
    public void afterValidation(JsonApiContext context, Set<ConstraintViolation<?>> violations) {
        chain.forEach(handler -> handler.afterValidation(context, violations));
    }

    @Override
    public void afterFind(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.afterFind(context, entity));
    }

    @Override
    public void beforePersist(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.beforePersist(context, entity));
    }

    @Override
    public void afterPersist(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.afterPersist(context, entity));
    }

    @Override
    public void beforeUpdate(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.beforeUpdate(context, entity));
    }

    @Override
    public void afterUpdate(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.afterUpdate(context, entity));
    }

    @Override
    public void beforeMerge(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.beforeMerge(context, entity));
    }

    @Override
    public void afterMerge(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.afterMerge(context, entity));
    }

    @Override
    public void beforeDelete(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.beforeDelete(context, entity));
    }

    @Override
    public void afterDelete(JsonApiContext context, Object entity) {
        chain.forEach(handler -> handler.afterDelete(context, entity));
    }

    @Override
    public void beforeResponse(JsonApiContext context) {
        chain.forEach(handler -> handler.beforeResponse(context));
    }

}
