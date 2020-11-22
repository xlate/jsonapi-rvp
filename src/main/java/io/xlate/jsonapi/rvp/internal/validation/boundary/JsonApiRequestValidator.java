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

import static java.util.function.Predicate.not;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.persistence.metamodel.Attribute;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.ws.rs.HttpMethod;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.rs.boundary.JsonApiError;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;

public class JsonApiRequestValidator implements ConstraintValidator<ValidJsonApiRequest, JsonApiRequest> {

    public static final String KEY_DATA = "data";
    public static final String KEY_META = "meta";

    public static final String KEY_JSONAPI = "jsonapi";
    public static final String KEY_INCLUDED = "included";

    public static final String KEY_ID = "id";
    public static final String KEY_TYPE = "type";
    public static final String KEY_ATTRIBUTES = "attributes";
    public static final String KEY_RELATIONSHIPS = "relationships";

    private static final String PATH_DATA = '/' + KEY_DATA;

    private static final List<String> topLevelRequiredKeys = Arrays.asList(KEY_DATA,
                                                                           KEY_META);

    private static final List<String> topLevelOptionalKeys = Arrays.asList(KEY_JSONAPI,
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
        validStructure = validData(value, context, validStructure);

        return validStructure;
    }

    boolean validTopLevel(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {

        validStructure = validTopLevelKeys(value, context, validStructure);
        validStructure = validTopLevelRequired(value, context, validStructure);

        return validStructure;
    }

    boolean validTopLevelKeys(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();
        AtomicInteger invalidKeys = new AtomicInteger(0);

        document.keySet().stream().filter(not(topLevelKeys::contains)).forEach(key -> {
            invalidKeys.incrementAndGet();
            context.buildConstraintViolationWithTemplate(""
                    + "A resource update document may ONLY contain these top-level members: "
                    + "`data`, `meta`, `jsonapi`, `included`")
                   .addPropertyNode("/" + key)
                   .addConstraintViolation();
        });

        if (invalidKeys.get() > 0) {
            validStructure = false;
        }

        return validStructure;
    }

    boolean validTopLevelRequired(JsonApiRequest value, ConstraintValidatorContext context, boolean validStructure) {
        JsonObject document = value.getDocument();

        if (!document.containsKey(KEY_DATA)) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "A resource update document MUST contain a top-level `data` member")
                   .addPropertyNode("/data")
                   .addConstraintViolation();
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

        if (data.getValueType() != ValueType.OBJECT) {
            context.buildConstraintViolationWithTemplate(""
                    + "Primary data MUST be a single resource object for requests that target single resources")
                   .addPropertyNode(PATH_DATA)
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
            case KEY_META:
                break;
            default:
                validStructure = false;
                context.buildConstraintViolationWithTemplate(""
                        + "A resource update object may only contain these members: "
                        + "`id`, `type`, `attributes`, `relationships`, `meta`")
                       .addPropertyNode(PATH_DATA + '/' + key)
                       .addConstraintViolation();
                break;
            }
        }

        validStructure = validIdentifier(value, value.getEntityMeta(), resource, context, validStructure, PATH_DATA);

        if (resource.containsKey(KEY_ATTRIBUTES)) {
            validStructure = validAttributes(value, resource.get(KEY_ATTRIBUTES), context, validStructure);
        }

        if (resource.containsKey(KEY_RELATIONSHIPS)) {
            validStructure = validRelationships(value, resource.get(KEY_RELATIONSHIPS), context, validStructure);
        }

        return validStructure;
    }

    boolean validIdentifier(JsonApiRequest value,
                            EntityMeta meta,
                            JsonObject resource,
                            ConstraintValidatorContext context,
                            boolean validStructure,
                            String propertyContext) {

        final JsonValue type = resource.get(KEY_TYPE);
        final JsonValue id = resource.get(KEY_ID);

        if (type == null) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate("Object must contain a `type` member")
                   .addPropertyNode(propertyContext)
                   .addConstraintViolation();
        } else if (type.getValueType() != ValueType.STRING) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate("The value of the `type` member must be a string")
                   .addPropertyNode(propertyContext + "/type")
                   .addConstraintViolation();
        } else if (!((JsonString) type).getString().equals(meta.getResourceType())) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate(""
                    + "Object's `type` is not valid for its context")
                   .addPropertyNode(propertyContext + "/type")
                   .addConstraintViolation();
        }

        if (id == null && !value.isRequestMethod(HttpMethod.POST)) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate("Object must contain an `id` member")
                   .addPropertyNode(propertyContext)
                   .addConstraintViolation();
        } else if (id != null && id.getValueType() != ValueType.STRING) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate("The value of the `id` member must be a string")
                   .addPropertyNode(propertyContext + "/id")
                   .addConstraintViolation();
        }

        return validStructure;
    }

    boolean validAttributes(JsonApiRequest value, JsonValue attributesValue, ConstraintValidatorContext context, boolean validStructure) {

        if (attributesValue.getValueType() != ValueType.OBJECT) {
            context.buildConstraintViolationWithTemplate(""
                    + "The value of the `attributes` key MUST be an object (an \"attributes object\")")
                   .addPropertyNode(JsonApiError.DATA_ATTRIBUTES_POINTER)
                   .addConstraintViolation();
            return false;
        }

        return attributesValue.asJsonObject()
                              .entrySet()
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
                    + "Invalid attribute name")
                   .addPropertyNode(JsonApiError.attributePointer(attributeKey))
                   .addConstraintViolation();
            return false;
        }

        EntityMeta meta = value.getEntityMeta();

        if (!meta.getAttributeNames().contains(attributeKey)) {
            context.buildConstraintViolationWithTemplate(""
                    + "No such attribute")
                   .addPropertyNode(JsonApiError.attributePointer(attributeKey))
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

    boolean validRelationships(JsonApiRequest value,
                               JsonValue relationshipsValue,
                               ConstraintValidatorContext context,
                               boolean validStructure) {

        if (relationshipsValue.getValueType() != ValueType.OBJECT) {
            context.buildConstraintViolationWithTemplate(""
                    + "The value of the `relationships` member MUST be an object")
                   .addPropertyNode(JsonApiError.DATA_RELATIONSHIPS_POINTER)
                   .addConstraintViolation();
            return false;
        }

        return relationshipsValue.asJsonObject()
                                 .entrySet()
                                 .stream()
                                 .map(attribute -> validRelationship(value, attribute, context, validStructure))
                                 .filter(Boolean.FALSE::equals)
                                 .findFirst()
                                 .orElse(validStructure);
    }

    boolean validRelationship(JsonApiRequest value,
                              Entry<String, JsonValue> relationshipEntry,
                              ConstraintValidatorContext context,
                              boolean validStructure) {

        final String relationshipName = relationshipEntry.getKey();

        if (!validMemberName(relationshipName)) {
            context.buildConstraintViolationWithTemplate(""
                    + "Invalid relationship name")
                   .addPropertyNode(JsonApiError.relationshipPointer(relationshipName))
                   .addConstraintViolation();
            return false;
        }

        EntityMeta meta = value.getEntityMeta();

        if (!meta.isRelatedTo(relationshipName)) {
            context.buildConstraintViolationWithTemplate(""
                    + "No such relationship")
                   .addPropertyNode(JsonApiError.relationshipPointer(relationshipName))
                   .addConstraintViolation();
            return false;
        }

        JsonValue relationshipValue = relationshipEntry.getValue();

        if (relationshipValue.getValueType() != ValueType.OBJECT) {
            context.buildConstraintViolationWithTemplate(""
                    + "The value of a `relationships` entry MUST be an object")
                   .addPropertyNode(JsonApiError.relationshipPointer(relationshipName))
                   .addConstraintViolation();
            return false;
        }

        JsonObject relationship = relationshipValue.asJsonObject();

        if (!relationship.containsKey(KEY_DATA)) {
            context.buildConstraintViolationWithTemplate(""
                    + "Relationship `data` missing")
                   .addPropertyNode(JsonApiError.relationshipPointer(relationshipName) + PATH_DATA)
                   .addConstraintViolation();
            return false;
        }

        JsonValue relationshipData = relationship.get(KEY_DATA);
        EntityMeta relatedMeta = value.getModel().getEntityMeta(meta.getRelatedEntityClass(relationshipName));
        Attribute<Object, ?> entityAttribute = meta.getEntityType().getAttribute(relationshipName);
        ValueType receivedType = relationshipData.getValueType();

        if (allowedTypes(entityAttribute).contains(receivedType)) {
            switch (receivedType) {
            case ARRAY:
                // validate array entries are resource id objects of correct type
                break;
            case OBJECT:
                // validate the resource id object is correct type
                validStructure = validIdentifier(value,
                                                 relatedMeta,
                                                 relationshipData.asJsonObject(),
                                                 context,
                                                 validStructure,
                                                 JsonApiError.relationshipPointer(relationshipName) + PATH_DATA);
                break;
            default:
                // NULL type
                break;
            }
        } else {
            validStructure = false;

            if (entityAttribute.isCollection()) {
                context.buildConstraintViolationWithTemplate(""
                        + "Value of `data` must be an array for this relationship")
                       .addPropertyNode(JsonApiError.relationshipPointer(relationshipName) + PATH_DATA)
                       .addConstraintViolation();
            } else {
                context.buildConstraintViolationWithTemplate(""
                        + "Value of `data` must be an object for this relationship")
                       .addPropertyNode(JsonApiError.relationshipPointer(relationshipName) + PATH_DATA)
                       .addConstraintViolation();
            }
        }

        return validStructure;
    }

    Set<ValueType> allowedTypes(Attribute<Object, ?> entityAttribute) {
        if (entityAttribute.isCollection()) {
            return Set.of(ValueType.ARRAY);
        }

        return Set.of(ValueType.NULL, ValueType.OBJECT);
    }

    boolean validMemberName(String name) {
        Pattern validPattern = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_ -]*[a-zA-Z0-9]+$");
        return validPattern.matcher(name).matches();
    }

}
