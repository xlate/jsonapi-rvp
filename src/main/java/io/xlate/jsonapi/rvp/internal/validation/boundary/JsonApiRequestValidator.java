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

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.ws.rs.HttpMethod;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.rs.boundary.JsonApiError;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;

public class JsonApiRequestValidator implements ConstraintValidator<ValidJsonApiRequest, JsonApiRequest> {

    public static final String KEY_DATA = "data";
    public static final String KEY_ERRORS = "errors";
    public static final String KEY_META = "meta";

    public static final String KEY_JSONAPI = "jsonapi";
    public static final String KEY_LINKS = "links";
    public static final String KEY_INCLUDED = "included";

    public static final String KEY_ID = "id";
    public static final String KEY_TYPE = "type";
    public static final String KEY_ATTRIBUTES = "attributes";
    public static final String KEY_RELATIONSHIPS = "relationships";

    private static final List<String> topLevelRequiredKeys = Arrays.asList(KEY_DATA,
                                                                           KEY_ERRORS,
                                                                           KEY_META);

    private static final List<String> topLevelOptionalKeys = Arrays.asList(KEY_JSONAPI,
                                                                           KEY_LINKS,
                                                                           KEY_INCLUDED);

    private static final List<String> topLevelKeys = new ArrayList<>(6);

    static {
        topLevelKeys.addAll(topLevelRequiredKeys);
        topLevelKeys.addAll(topLevelOptionalKeys);
    }

    @SuppressWarnings("unused")
    private ValidJsonApiRequest annotation;

    @Override
    public void initialize(ValidJsonApiRequest constraintAnnotation) {
        this.annotation = constraintAnnotation;
    }

    @Override
    public boolean isValid(JsonApiRequest value, ConstraintValidatorContext context) {
        JsonObject document = value.getDocument();

        if (document == null) {
            return true;
        }

        boolean validStructure = true;

        validStructure = validTopLevel(value, context, validStructure);
        validStructure = validLinks(value, context, validStructure);
        validStructure = validData(value, context, validStructure);

        if (validStructure) {
            validStructure = validAttributes(value, context, validStructure);
        }

        return validStructure;
    }

    boolean validTopLevel(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {

        validStructure = validTopLevelKeys(value, context, validStructure);
        validStructure = validTopLevelRequired(value, context, validStructure);
        validStructure = validTopLevelExclusive(value, context, validStructure);

        return validStructure;
    }

    boolean validTopLevelKeys(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        for (String key : document.keySet()) {
            if (!topLevelKeys.contains(key)) {
                validStructure = false;
                context.buildConstraintViolationWithTemplate(""
                        + "A document may ONLY contain these top-level members: "
                        + "`data`, `errors`, `meta`, `jsonapi`, `links`, `included`")
                       .addPropertyNode("/" + key)
                       .addConstraintViolation();
            }
        }

        return validStructure;
    }

    boolean validTopLevelRequired(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();
        boolean hasRequired = false;

        for (String key : document.keySet()) {
            if (topLevelRequiredKeys.contains(key)) {
                hasRequired = true;
                break;
            }
        }

        if (!hasRequired) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "A document MUST contain at least one of the following top-level members: "
                    + "`data`, `errors`, `meta`")
                   .addPropertyNode("/")
                   .addConstraintViolation();
        }

        return validStructure;
    }

    boolean validTopLevelExclusive(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        if (Stream.of(KEY_DATA, KEY_ERRORS).allMatch(document::containsKey)) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The members `data` and `errors` MUST NOT coexist in the same document")
                   .addPropertyNode("/")
                   .addConstraintViolation();
        }

        return validStructure;
    }

    boolean validLinks(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        if (!document.containsKey(KEY_LINKS)) {
            return validStructure;
        }

        JsonValue links = document.get(KEY_LINKS);

        if (links.getValueType() != ValueType.OBJECT) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The value of each links member MUST be an object")
                   .addPropertyNode("/" + KEY_LINKS)
                   .addConstraintViolation();
        } else {
            for (String key : ((JsonObject) links).keySet()) {
                switch (key) {
                case "self":
                case "related":
                case "first":
                case "last":
                case "prev":
                case "next":
                    break;
                default:
                    validStructure = false;
                    context.buildConstraintViolationWithTemplate(""
                            + "The top-level links object MAY contain the following members: "
                            + "`self`, `related`, pagination links: `first`, `last`, `prev`, `next`")
                           .addPropertyNode("/links/" + key)
                           .addConstraintViolation();
                    break;
                }
            }
        }

        return validStructure;
    }

    boolean validData(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        if (!document.containsKey(KEY_DATA)) {
            return validStructure;
        }

        // `data` must always be a single resource for client update operations in JSON API 1.0
        JsonValue data = document.get(KEY_DATA);

        // TODO: Allow 'null' for relationship updates
        if (data.getValueType() != ValueType.OBJECT) {
            context.buildConstraintViolationWithTemplate(""
                    + "Primary data MUST be a single resource object, a single resource identifier object, or null, "
                    + "for requests that target single resources")
                   .addPropertyNode("/data")
                   .addConstraintViolation();

            return false;
        }

        JsonObject resource = (JsonObject) data;

        for (String key : resource.keySet()) {
            switch (key) {
            case KEY_ID:
            case KEY_TYPE:
            case KEY_ATTRIBUTES:
            case KEY_RELATIONSHIPS:
            case KEY_LINKS:
            case KEY_META:
                break;
            default:
                validStructure = false;
                context.buildConstraintViolationWithTemplate(""
                        + "A resource object may only contain these top-level members: "
                        + "`id`, `type`, `attributes`, `relationships`, `links`, `meta`")
                       .addPropertyNode("/data/" + key)
                       .addConstraintViolation();
                break;
            }
        }

        JsonValue type = resource.get(KEY_TYPE);
        JsonValue id = resource.get(KEY_ID);

        if (type == null
                || (id == null && !value.isRequestMethod(HttpMethod.POST))) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "A resource object MUST contain at least the following top-level members: "
                    + "`id`, `type`")
                   .addPropertyNode("/data")
                   .addConstraintViolation();
        }

        if (type != null && type.getValueType() != ValueType.STRING) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The value of the type member MUST be a string")
                   .addPropertyNode("/data/type")
                   .addConstraintViolation();
        }

        if (id != null && id.getValueType() != ValueType.STRING) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The value of the id member MUST be a string")
                   .addPropertyNode("/data/id")
                   .addConstraintViolation();
        }

        return validStructure;
    }

    boolean validAttributes(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        if (!document.containsKey(KEY_DATA)) {
            return validStructure;
        }

        JsonObject data = document.getJsonObject(KEY_DATA);

        if (!data.containsKey(KEY_ATTRIBUTES)) {
            return validStructure;
        }

        JsonValue attributesValue = data.get(KEY_ATTRIBUTES);

        if (attributesValue.getValueType() != ValueType.OBJECT) {
            context.buildConstraintViolationWithTemplate(""
                    + "The value of the `attributes` key MUST be an object (an \"attributes object\")")
                   .addPropertyNode(JsonApiError.DATA_ATTRIBUTES_POINTER)
                   .addConstraintViolation();
            return false;
        }

        JsonObject attributes = (JsonObject) attributesValue;

        return attributes.entrySet()
                         .stream()
                         .map(attribute -> validAttribute(value, attribute, context, validStructure))
                         .filter(Boolean.FALSE::equals)
                         .findFirst()
                         .orElse(validStructure);
    }

    boolean validAttribute(JsonApiRequest value,
                           Entry<String, JsonValue> attribute,
                           ConstraintValidatorContext context,
                           boolean validStructure) {

        final String attributeKey = attribute.getKey();

        if (!validMemberName(attributeKey)) {
            context.buildConstraintViolationWithTemplate(""
                    + "Invalid attribute name `" + attributeKey + "`")
                   .addPropertyNode(JsonApiError.DATA_ATTRIBUTES_POINTER + attributeKey)
                   .addConstraintViolation();
            return false;
        }

        EntityMeta meta = value.getEntityMeta();

        if (!meta.getAttributeNames().contains(attributeKey)) {
            context.buildConstraintViolationWithTemplate(""
                    + "No such attribute `" + attributeKey + "`")
                   .addPropertyNode(JsonApiError.DATA_ATTRIBUTES_POINTER + attributeKey)
                   .addConstraintViolation();
            return false;
        }

        PropertyDescriptor property = meta.getPropertyDescriptor(attributeKey);
        Class<?> propertyType = property.getPropertyType();
        JsonValue attributeValue = attribute.getValue();

        switch (attributeValue.getValueType()) {
        case ARRAY:
        case OBJECT:
            // TODO: Add support for object and array attributes
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "Array and Object attributes not supported.")
                   .addPropertyNode(JsonApiError.attributePointer(attributeKey))
                   .addConstraintViolation();
            break;
        case FALSE:
        case TRUE:
            if (!Boolean.class.isAssignableFrom(propertyType)) {
                validStructure = false;
                addIncompatibleDataError(context, attributeKey);
            }
            break;
        case NULL:
            break;
        case NUMBER:
            if (!Number.class.isAssignableFrom(propertyType)) {
                validStructure = false;
                addIncompatibleDataError(context, attributeKey);
            }
            break;
        case STRING:
            if (!propertyType.equals(String.class)) {
                String jsonString = ((JsonString) attributeValue).getString();
                try {
                    Method valueOf = propertyType.getMethod("valueOf", String.class);
                    valueOf.invoke(null, jsonString);
                } catch (@SuppressWarnings("unused") Exception e) {
                    validStructure = false;
                    addIncompatibleDataError(context, attributeKey);
                }
            }
            break;
        }

        return validStructure;
    }

    void addIncompatibleDataError(ConstraintValidatorContext context, String attributeKey) {
        context.buildConstraintViolationWithTemplate(""
                + "Incompatible data type")
               .addPropertyNode(JsonApiError.attributePointer(attributeKey))
               .addConstraintViolation();
    }

    boolean validMemberName(String name) {
        Pattern validPattern = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_ -]*[a-zA-Z0-9]+$");
        return validPattern.matcher(name).matches();
    }
}
