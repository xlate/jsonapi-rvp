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
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.persistence.metamodel.Attribute;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.ws.rs.HttpMethod;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiError;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;

public class JsonApiRequestValidator implements ConstraintValidator<ValidJsonApiRequest, JsonApiRequest> {

    private static final Logger LOGGER = Logger.getLogger(JsonApiRequestValidator.class.getName());
    private static final Object INVALID_VALUE = new Object();

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

    private static final Set<Class<?>> NUMBER_PRIMITIVES = Set.of(Byte.TYPE,
                                                                  Character.TYPE,
                                                                  Double.TYPE,
                                                                  Float.TYPE,
                                                                  Integer.TYPE,
                                                                  Long.TYPE,
                                                                  Short.TYPE);

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
            // TODO: Allow `null` for relationship end points
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
                    + "The value of the `attributes` key MUST be an object")
                   .addPropertyNode(JsonApiError.DATA_ATTRIBUTES_POINTER)
                   .addConstraintViolation();
            return false;
        }

        return attributesValue.asJsonObject()
                              .entrySet()
                              .stream()
                              .map(attribute -> validAttribute(value, attribute, context))
                              .filter(Boolean.FALSE::equals)
                              .count() == 0
                && validStructure;
    }

    boolean validAttribute(JsonApiRequest value,
                           Entry<String, JsonValue> attribute,
                           ConstraintValidatorContext context) {

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
        Set<ValueType> allowedTypes = allowedAttributeTypes(propertyType);
        boolean valid = true;

        if (allowedTypes.contains(attributeValue.getValueType())) {
            if (allowedTypes.contains(ValueType.STRING)) {
                valid = validateStringAttribute(context, propertyType, attributeKey, attributeValue);
            }
        } else {
            valid = false;
            String message = allowedTypes.stream()
                                         .filter(not(ValueType.NULL::equals))
                                         .map(ValueType::name)
                                         .map(String::toLowerCase)
                                         .sorted()
                                         .collect(Collectors.joining(" or ",
                                                                     "Received value type `"
                                                                             + attributeValue.getValueType().name().toLowerCase()
                                                                             + "`, expected: ",
                                                                     ""));
            addIncompatibleDataError(context, message, attributeKey);
        }

        return valid;
    }

    Set<ValueType> allowedAttributeTypes(Class<?> propertyType) {
        if (Boolean.class.isAssignableFrom(propertyType) || Boolean.TYPE.equals(propertyType)) {
            return propertyType.isPrimitive()
                    ? Set.of(ValueType.TRUE, ValueType.FALSE)
                    : Set.of(ValueType.TRUE, ValueType.FALSE, ValueType.NULL);
        }

        if (Number.class.isAssignableFrom(propertyType)
                || Character.class.isAssignableFrom(propertyType)
                || NUMBER_PRIMITIVES.contains(propertyType)) {

            return propertyType.isPrimitive()
                    ? Set.of(ValueType.NUMBER)
                    : Set.of(ValueType.NUMBER, ValueType.NULL);
        }

        return Set.of(ValueType.STRING, ValueType.NULL);
    }

    boolean validateStringAttribute(ConstraintValidatorContext context, Class<?> propertyType, String attributeKey, JsonValue attributeValue) {
        boolean valid = true;

        var parsers = Stream.concat(Arrays.stream(propertyType.getConstructors()),
                                    Arrays.stream(propertyType.getMethods()))
                            .filter(method -> Modifier.isStatic(method.getModifiers()))
                            .filter(method -> Objects.equals(method.getParameterCount(), 1))
                            .filter(this::parsingMethod)
                            .collect(Collectors.toMap(Executable::getName, method -> method));

        StringParser parser = objectParserMethod(parsers, propertyType);

        try {
            if (parser.parse(((JsonString) attributeValue).getString()) == INVALID_VALUE) {
                valid = false;
                addIncompatibleDataError(context, "Incompatible data type", attributeKey);
            }
        } catch (Exception e) {
            LOGGER.finer(() -> "Error parsing string attribute: " + e.getMessage());
            valid = false;
            addIncompatibleDataError(context, "Incompatible data type", attributeKey);
        }

        return valid;
    }

    boolean parsingMethod(Executable method) {
        Class<?> paramType = method.getParameterTypes()[0];

        if (paramType == String.class || paramType == CharSequence.class) {
            return true;
        }

        if (paramType == Instant.class && "from".equals(method.getName())) {
            return true;
        }

        return false;
    }

    StringParser objectParserMethod(Map<String, Executable> methods, Class<?> propertyType) {
        if (String.class.equals(propertyType)) {
            return value -> value;
        }

        if (propertyType.isAssignableFrom(OffsetDateTime.class)) {
            return OffsetDateTime::parse;
        }

        if (methods.containsKey("from") && methods.get("from").getParameterTypes()[0].equals(Instant.class)) {
            return value -> {
                Instant instant = OffsetDateTime.parse(value).toInstant();
                return ((Method) methods.get("from")).invoke(null, instant);
            };
        }

        if (methods.containsKey("valueOf")) {
            return value -> ((Method) methods.get("valueOf")).invoke(null, value);
        }

        if (methods.containsKey("parse")) {
            return value -> ((Method) methods.get("parse")).invoke(null, value);
        }

        if (methods.containsKey("<init>")) {
            return value -> Constructor.class.cast(methods.get("<init>")).newInstance(value);
        }

        return value -> INVALID_VALUE;
    }

    @FunctionalInterface
    interface StringParser {
        Object parse(String value) throws Exception;
    }

    void addIncompatibleDataError(ConstraintValidatorContext context, String message, String attributeKey) {
        context.buildConstraintViolationWithTemplate(message)
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

        if (allowedRelationshipTypes(entityAttribute).contains(receivedType)) {
            switch (receivedType) {
            case ARRAY:
                // validate array entries are resource id objects of correct type
                validStructure = validIdentifiers(value,
                                                 relatedMeta,
                                                 relationshipData.asJsonArray(),
                                                 context,
                                                 validStructure,
                                                 JsonApiError.relationshipPointer(relationshipName) + PATH_DATA);
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

    Set<ValueType> allowedRelationshipTypes(Attribute<Object, ?> entityAttribute) {
        if (entityAttribute.isCollection()) {
            return Set.of(ValueType.ARRAY);
        }

        return Set.of(ValueType.NULL, ValueType.OBJECT);
    }

    boolean validIdentifiers(JsonApiRequest value,
                             EntityMeta meta,
                             JsonArray relationshipData,
                             ConstraintValidatorContext context,
                             boolean validStructure,
                             String propertyContext) {

        int index = 0;

        for (JsonValue entry : relationshipData.asJsonArray()) {
            final String path = String.format("%s/%d", propertyContext, index++);

            if (entry.getValueType() != ValueType.OBJECT) {
                validStructure = false;
                context.buildConstraintViolationWithTemplate(""
                        + "Expected object, but received " + entry.getValueType().name().toLowerCase())
                       .addPropertyNode(path)
                       .addConstraintViolation();
            } else {
                validStructure = validIdentifier(value,
                                                 meta,
                                                 entry.asJsonObject(),
                                                 context,
                                                 validStructure,
                                                 path);
            }
        }

        return validStructure;
    }

    boolean validMemberName(String name) {
        Pattern validPattern = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_ -]*[a-zA-Z0-9]+$");
        return validPattern.matcher(name).matches();
    }

}
