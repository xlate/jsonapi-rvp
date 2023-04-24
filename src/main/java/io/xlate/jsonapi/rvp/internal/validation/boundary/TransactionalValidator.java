package io.xlate.jsonapi.rvp.internal.validation.boundary;

import java.util.Arrays;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.core.Response.Status;

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
            validationGroups[groupCount - 1] = Class.forName("jakarta.ws.rs." + method);
        } catch (ClassNotFoundException e) {
            throw new JsonApiErrorException(Status.INTERNAL_SERVER_ERROR, "Server Error", e.getMessage());
        }

        return this.validator.validate(entity, validationGroups);
    }

}
