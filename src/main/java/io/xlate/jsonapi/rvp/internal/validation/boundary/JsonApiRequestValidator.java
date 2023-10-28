package io.xlate.jsonapi.rvp.internal.validation.boundary;

import static java.util.function.Predicate.not;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;
import jakarta.persistence.metamodel.Attribute;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.ws.rs.HttpMethod;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.rs.boundary.ResourceObjectReader;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiError;
import io.xlate.jsonapi.rvp.internal.rs.entity.JsonApiRequest;

public class JsonApiRequestValidator implements ConstraintValidator<ValidJsonApiRequest, JsonApiRequest> {

    private static final Logger LOGGER = Logger.getLogger(JsonApiRequestValidator.class.getName());

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
        } else if (id != null && !readableIdentifier(meta, (JsonString) id)) {
            validStructure = false;
            context.buildConstraintViolationWithTemplate("The value of the `id` member is invalid")
                   .addPropertyNode(propertyContext + "/id")
                   .addConstraintViolation();
        }

        return validStructure;
    }

    boolean readableIdentifier(EntityMeta meta, JsonString id) {
        try {
            return id != null && meta.readId(id.getString()) != null;
        } catch (Exception e) {
            return false;
        }
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
        final EntityMeta meta = value.getEntityMeta();

        if (!meta.hasAttribute(attributeKey)) {
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
            if (allowedTypes.contains(ValueType.STRING) && !JsonValue.NULL.equals(attributeValue)) {
                valid = validateStringAttribute(context, meta, attributeKey, attributeValue);
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
                || ResourceObjectReader.NUMBER_PRIMITIVES.contains(propertyType)) {

            return propertyType.isPrimitive()
                    ? Set.of(ValueType.NUMBER)
                    : Set.of(ValueType.NUMBER, ValueType.NULL);
        }

        return Set.of(ValueType.STRING, ValueType.NULL);
    }

    boolean validateStringAttribute(ConstraintValidatorContext context, EntityMeta meta, String attributeKey, JsonValue attributeValue) {
        boolean valid = true;

        try {
            if (meta.getReaders().get(attributeKey).apply(((JsonString) attributeValue).getString()) == null) {
                valid = false;
                addIncompatibleDataError(context, "Invalid format", attributeKey);
            }
        } catch (Exception e) {
            LOGGER.finer(() -> "Error parsing string attribute: " + e.getMessage());
            valid = false;
            addIncompatibleDataError(context, "Invalid format", attributeKey);
        }

        return valid;
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
        final EntityMeta meta = value.getEntityMeta();

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

}
