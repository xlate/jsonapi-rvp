package io.xlate.jsonapi.rs.internal.boundary;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.validation.Constraint;
import javax.validation.Payload;

@Target(TYPE)
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = { JsonApiUriParametersValidator.class })
public @interface ValidJsonApiUriParameters {

    String message() default "{io.xlate.jsonapi.rvp.constraints.ValidJsonApiStructure.message}";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };

}
