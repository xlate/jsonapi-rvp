package io.xlate.jsonapi.rvp.internal.validation.boundary;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target(TYPE)
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = { JsonApiRequestValidator.class })
public @interface ValidJsonApiRequest {

    String message() default "{io.xlate.jsonapi.rvp.constraints.ValidJsonApiStructure.message}";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };

}
