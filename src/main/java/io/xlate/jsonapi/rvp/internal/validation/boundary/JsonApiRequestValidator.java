/*******************************************************************************
 * Copyright (C) 2018 xlate.io LLC, http://www.xlate.io
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package io.xlate.jsonapi.rvp.internal.boundary;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.ws.rs.HttpMethod;

import io.xlate.jsonapi.rvp.internal.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.entity.JsonApiRequest;

public class JsonApiRequestValidator implements ConstraintValidator<ValidJsonApiRequest, JsonApiRequest> {

    private static final List<String> topLevelRequiredKeys = Arrays.asList("data",
                                                                           "errors",
                                                                           "meta");

    private static final List<String> topLevelOptionalKeys = Arrays.asList("jsonapi",
                                                                           "links",
                                                                           "included");

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

        if (document.containsKey("data") && document.containsKey("errors")) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The members `data` and `errors` MUST NOT coexist in the same document.")
                   .addPropertyNode("/")
                   .addConstraintViolation();
        }

        return validStructure;
    }

    boolean validLinks(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        if (!document.containsKey("links")) {
            return validStructure;
        }

        JsonValue links = document.get("links");

        if (links.getValueType() != ValueType.OBJECT) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The value of each links member MUST be an object.")
                   .addPropertyNode("/links")
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

        if (!document.containsKey("data")) {
            return validStructure;
        }

        // `data` must always be a single resource for client update operations in JSON API 1.0
        JsonValue data = document.get("data");

        // TODO: Allow 'null' for relationship updates
        if (data.getValueType() != ValueType.OBJECT) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "Primary data MUST be a single resource object, a single resource identifier object, or null, "
                    + "for requests that target single resources")
                   .addPropertyNode("/data")
                   .addConstraintViolation();
        } else {
            JsonObject resource = (JsonObject) data;

            for (String key : resource.keySet()) {
                switch (key) {
                case "id":
                case "type":
                case "attributes":
                case "relationships":
                case "links":
                case "meta":
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

            JsonValue type = resource.get("type");
            JsonValue id = resource.get("id");

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
                        + "The value of the type member MUST be a string.")
                       .addPropertyNode("/data/type")
                       .addConstraintViolation();
            }

            if (id != null && id.getValueType() != ValueType.STRING) {
                validStructure = false;
                context.buildConstraintViolationWithTemplate(""
                        + "The value of the id member MUST be a string.")
                       .addPropertyNode("/data/id")
                       .addConstraintViolation();
            }
        }

        return validStructure;
    }

    boolean validAttributes(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject data = value.getDocument().getJsonObject("data");

        if (!data.containsKey("attributes")) {
            return validStructure;
        }

        JsonValue attributesValue = data.get("attributes");

        if (attributesValue.getValueType() != ValueType.OBJECT) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "The value of the `attributes` key MUST be an object (an \"attributes object\").")
                   .addPropertyNode("/data/attributes")
                   .addConstraintViolation();
        } else {
            JsonObject attributes = (JsonObject) attributesValue;
            EntityMeta meta = value.getEntityMeta();
            //lass<Object> entityClass = value.getEntityMeta().getEntityClass();

            for (String attributeKey : attributes.keySet()) {
                if (validMemberName(attributeKey)) {
                    PropertyDescriptor property = meta.getPropertyDescriptor(attributeKey);
                    Class<?> propertyType = property.getPropertyType();
                    JsonValue attributeValue = attributes.get(attributeKey);

                    switch (attributeValue.getValueType()) {
                    case ARRAY:
                    case OBJECT:
                        // TODO: Add support for object and array attributes
                        context.buildConstraintViolationWithTemplate(""
                                + "Array and Object attributes not supported.")
                               .addPropertyNode("/data/attributes/" + attributeKey)
                               .addConstraintViolation();
                        break;
                    case FALSE:
                    case TRUE:
                        if (!Boolean.class.isAssignableFrom(propertyType)) {
                            context.buildConstraintViolationWithTemplate(""
                                    + "Incompatible data type")
                                   .addPropertyNode("/data/attributes/" + attributeKey)
                                   .addConstraintViolation();
                        }
                        break;
                    case NULL:
                        break;
                    case NUMBER:
                        if (!Number.class.isAssignableFrom(propertyType)) {
                            context.buildConstraintViolationWithTemplate(""
                                    + "Incompatible data type")
                                   .addPropertyNode("/data/attributes/" + attributeKey)
                                   .addConstraintViolation();
                        }
                        break;
                    case STRING:
                        String jsonString = ((JsonString) attributeValue).getString();
                        try {
                            Method valueOf = propertyType.getMethod("valueOf", String.class);
                            valueOf.invoke(null, jsonString);
                        } catch (@SuppressWarnings("unused") Exception e) {
                            context.buildConstraintViolationWithTemplate(""
                                    + "Incompatible data type")
                                   .addPropertyNode("/data/attributes/" + attributeKey)
                                   .addConstraintViolation();
                        }
                        break;
                    }
                } else {
                    validStructure = false;
                    context.buildConstraintViolationWithTemplate(""
                            + "Attribute name `" + attributeKey + "` contains in invalid character.")
                           .addPropertyNode("/data/attributes/" + attributeKey)
                           .addConstraintViolation();
                }
            }
        }

        return validStructure;
    }

    boolean validMemberName(String name) {
        Pattern validPattern = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_ -]*[a-zA-Z0-9]+$");
        return validPattern.matcher(name).matches();
    }
}
