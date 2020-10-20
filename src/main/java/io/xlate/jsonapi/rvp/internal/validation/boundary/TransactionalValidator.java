package io.xlate.jsonapi.rvp.internal.validation.boundary;

import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.transaction.Transactional.TxType;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

@ApplicationScoped
public class TransactionalValidator {

    @Inject
    protected Validator validator;

    @Transactional(value = TxType.REQUIRES_NEW)
    public <T> Set<ConstraintViolation<T>> validate(String method, T entity) {
        Class<?> group;
        try {
            group = Class.forName("javax.ws.rs." + method);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return this.validator.validate(entity, group);
    }

}
