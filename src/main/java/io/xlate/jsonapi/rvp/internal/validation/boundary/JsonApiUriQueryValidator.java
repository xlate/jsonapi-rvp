/*******************************************************************************
 * Copyright (C) 2018 xlate.io LLC, http://www.xlate.io
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package io.xlate.jsonapi.rvp.internal.validation.boundary;

import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.ws.rs.core.MultivaluedMap;

import io.xlate.jsonapi.rvp.JsonApiQuery;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;

public class JsonApiUriQueryValidator
        implements ConstraintValidator<ValidJsonApiQuery, JsonApiQuery> {

    private static final Logger LOGGER = Logger.getLogger(JsonApiUriQueryValidator.class.getName());

    @SuppressWarnings("unused")
    private ValidJsonApiQuery annotation;

    @Override
    public void initialize(ValidJsonApiQuery constraintAnnotation) {
        this.annotation = constraintAnnotation;
    }

    @Override
    public boolean isValid(JsonApiQuery value, ConstraintValidatorContext context) {
        boolean valid = true;

        MultivaluedMap<String, String> params = value.getUriInfo().getQueryParameters();
        String id = value.getId();

        valid = validateFields(value, context, valid);
        valid = validateFilters(value, context, valid);

        if (params.containsKey(JsonApiQuery.PARAM_INCLUDE)) {
            valid = validateInclude(value, params, context, valid);
        }

        if (params.containsKey(JsonApiQuery.PARAM_SORT)) {
            valid = validateSort(value, id, params, context, valid);
        }

        valid = validatePaging(id, JsonApiQuery.PARAM_PAGE_NUMBER, params, context, valid);
        valid = validatePaging(id, JsonApiQuery.PARAM_PAGE_SIZE, params, context, valid);

        valid = validatePaging(id, JsonApiQuery.PARAM_PAGE_OFFSET, params, context, valid);
        valid = validatePaging(id, JsonApiQuery.PARAM_PAGE_LIMIT, params, context, valid);

        return valid;
    }

    boolean validateInclude(JsonApiQuery value,
                            MultivaluedMap<String, String> params,
                            ConstraintValidatorContext context,
                            boolean valid) {

        EntityMeta meta = getEntityMeta(value);
        List<String> includeParams = params.get(JsonApiQuery.PARAM_INCLUDE);
        valid = validateSingle(JsonApiQuery.PARAM_INCLUDE, includeParams, context, valid);

        String includeParam = includeParams.get(0);
        Set<String> included = new HashSet<>();

        for (String attribute : includeParam.split(",")) {
            if (!included.contains(attribute) && !meta.isRelatedTo(attribute)) {
                valid = false;
                addViolation(context, JsonApiQuery.PARAM_INCLUDE, "Invalid relationship: `" + attribute + "`");
            }

            included.add(attribute);
        }

        return valid;
    }

    boolean validateFields(JsonApiQuery value, ConstraintValidatorContext context, boolean valid) {
        EntityMetamodel model = value.getModel();

        for (Entry<String, List<String>> fields : value.getFields().entrySet()) {
            String resourceType = fields.getKey();
            EntityMeta meta = model.getEntityMeta(resourceType);

            if (meta == null) {
                valid = false;
                addViolation(context, "fields[" + resourceType + "]", "Invalid resource type: `" + resourceType + "`");
            } else {
                for (String field : fields.getValue()) {
                    if (!meta.isField(field)) {
                        valid = false;
                        addViolation(context,
                                     "fields[" + resourceType + "]",
                                     "Invalid field: `" + field + "`");
                    }
                }
            }
        }

        return valid;
    }

    boolean validateFilters(JsonApiQuery value, ConstraintValidatorContext context, boolean valid) {
        EntityMetamodel model = value.getModel();

        for (Entry<String, String> filter : value.getFilters().entrySet()) {
            EntityMeta meta = value.getEntityMeta();
            String path = filter.getKey();
            String[] elements = path.split("\\.");
            boolean validFilter = true;

            for (int i = 0; i < elements.length && validFilter; i++) {
                if (i + 1 == elements.length) {
                    validFilter = meta.hasAttribute(elements[i])
                            || meta.getExposedIdAttribute().getName().equals(elements[i]);
                } else {
                    validFilter = (meta = getRelatedEntityMeta(model, meta, elements[i])) != null;
                }
            }

            if (!validFilter) {
                valid = false;
                addViolation(context, "filter[" + path + "]", "Filter path `" + path + "` is not valid");
            }
        }

        return valid;
    }

    public static EntityMeta getRelatedEntityMeta(EntityMetamodel model, EntityMeta meta, String relationshipJoin) {
        final String relationshipName;

        if (relationshipJoin.startsWith("+")) {
            // Left JOIN
            relationshipName = relationshipJoin.substring(1);
        } else {
            relationshipName = relationshipJoin;
        }

        if (meta.isRelatedTo(relationshipName)) {
            return model.getEntityMeta(meta.getRelatedEntityClass(relationshipName));
        }

        return null;
    }

    boolean validateSort(JsonApiQuery value,
                         String id,
                         MultivaluedMap<String, String> params,
                         ConstraintValidatorContext context,
                         boolean valid) {

        EntityMeta meta = getEntityMeta(value);

        if (id != null) {
            valid = false;
            addViolation(context, JsonApiQuery.PARAM_SORT, "Single resource can not be sorted");
        } else {
            List<String> sortParams = params.get(JsonApiQuery.PARAM_SORT);
            valid = validateSingle(JsonApiQuery.PARAM_SORT, sortParams, context, valid);

            String sortParam = sortParams.get(0);

            for (String sort : sortParam.split(",")) {
                boolean descending = sort.startsWith("-");
                String attribute = sort.substring(descending ? 1 : 0);

                if (!meta.hasAttribute(attribute)) {
                    LOGGER.log(Level.FINER, () -> "Invalid attribute name: `" + attribute + "`.");
                    valid = false;
                    addViolation(context, JsonApiQuery.PARAM_SORT, "Sort key `" + sort + "` is not an attribute");
                }
            }
        }

        return valid;
    }

    EntityMeta getEntityMeta(JsonApiQuery value) {
        EntityMeta meta = value.getEntityMeta();
        String relationshipName = value.getRelationshipName();

        if (relationshipName != null) {
            // Validate the `include` relative to the "related" entity URI
            meta = value.getModel().getEntityMeta(meta.getRelatedEntityClass(relationshipName));
        }

        return meta;
    }

    boolean validatePaging(String id,
                           String paramName,
                           MultivaluedMap<String, String> params,
                           ConstraintValidatorContext context,
                           boolean valid) {

        if (params.containsKey(paramName)) {
            if (id != null) {
                valid = false;
                addViolation(context, paramName, "Pagination not allowed for single resource requests");
            } else {
                List<String> pageParamValues = params.get(paramName);
                valid = validateSingle(paramName, pageParamValues, context, valid);

                try {
                    Integer.parseInt(pageParamValues.get(0));
                } catch (NumberFormatException e) {
                    LOGGER.log(Level.FINER, () -> "Invalid page parameter: `" + pageParamValues.get(0) + "`.");
                    valid = false;
                    addViolation(context, paramName, "Page parameter must be an integer");
                }
            }
        }
        return valid;
    }

    boolean validateSingle(String paramName,
                           List<String> paramValues,
                           ConstraintValidatorContext context,
                           boolean valid) {

        if (paramValues.size() > 1) {
            valid = false;
            addViolation(context, paramName, "Multiple `" + paramName + "` parameters are not supported");
        }
        return valid;
    }

    void addViolation(ConstraintValidatorContext context, String paramName, String message) {
        context.buildConstraintViolationWithTemplate(message)
               .addPropertyNode(paramName)
               .addConstraintViolation();
    }
}
