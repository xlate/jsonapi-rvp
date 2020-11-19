package io.xlate.jsonapi.rvp.internal.validation.boundary;

import java.util.Arrays;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.transaction.Transactional.TxType;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.core.Response.Status;

import io.xlate.jsonapi.rvp.internal.JsonApiErrorException;

@ApplicationScoped
public class TransactionalValidator {

    private final Validator validator;

    @Inject
    public TransactionalValidator(Validator validator) {
        super();
        this.validator = validator;
    }

    @Transactional(value = TxType.REQUIRES_NEW)
    public <T> Set<ConstraintViolation<T>> validate(String method, T entity, Class<?>... groups) {
        final int groupCount = groups.length + 1;
        final Class<?>[] validationGroups = Arrays.copyOf(groups, groupCount);

        try {
            validationGroups[groupCount - 1] = Class.forName("javax.ws.rs." + method);
        } catch (ClassNotFoundException e) {
            throw new JsonApiErrorException(Status.INTERNAL_SERVER_ERROR, "Server Error", e.getMessage());
        }

        return this.validator.validate(entity, validationGroups);
    }

}
