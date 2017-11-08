package io.xlate.jsonapi.rs;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.json.stream.JsonGenerator;
import javax.ws.rs.core.UriInfo;
import javax.xml.bind.DatatypeConverter;

public abstract class JsonApiSerializer<E extends JsonApiEntity> {

    private static final JsonArray emptyJsonArray = Json.createArrayBuilder().build();
    private final Class<E> type;
    private final String typeName;

    public JsonApiSerializer(Class<E> type, String typeName) {
        this.type = type;
        this.typeName = typeName;
    }

    public Class<E> getType() {
        return type;
    }

    public String getTypeName() {
        return typeName;
    }

    public JsonObject serialize(List<E> entities, UriInfo uriInfo) {
        return serialize(entities, uriInfo, true);
    }

    public JsonObject serialize(List<E> entities, UriInfo uriInfo, boolean withRelationships) {
        final JsonObjectBuilder root = Json.createObjectBuilder();
        final JsonArrayBuilder data = Json.createArrayBuilder();

        for (E entity : entities) {
            data.add(serializeData(entity, uriInfo, withRelationships));
        }

        root.add("data", data);

        final JsonArrayBuilder included = Json.createArrayBuilder();
        final Map<String, String> written = new HashMap<>();

        for (E entity : entities) {
            for (JsonValue entry : serializeIncluded(entity, uriInfo)) {
                JsonObject obj = (JsonObject) entry;

                String objId = obj.getString("id");
                String objType = obj.getString("type");

                if (!objType.equals(written.get(objId))) {
                    written.put(objId, objType);
                    included.add(entry);
                }
            }
        }

        if (!written.isEmpty()) {
            root.add("included", included);
        }

        return root.build();
    }

    public JsonObject serialize(E source, UriInfo uriInfo) {
        return serialize(source, uriInfo, true);
    }

    public JsonObject serialize(E source, UriInfo uriInfo, boolean withRelationships) {
        JsonObjectBuilder root = Json.createObjectBuilder();

        root.add("data", serializeData(source, uriInfo, withRelationships));

        final JsonArray included = serializeIncluded(source, uriInfo);

        if (!included.isEmpty()) {
            root.add("included", included);
        }

        return root.build();
    }

    public JsonObject serializeData(E source, UriInfo uriInfo, boolean withRelationships) {
        JsonObjectBuilder data = serializeIdentifier(source);

        data.add("attributes", serializeAttributes(source, uriInfo));

        if (withRelationships) {
            JsonObjectBuilder rel = serializeRelationships(source, uriInfo);

            if (rel != null) {
                data.add("relationships", rel);
            }
        }

        data.add("links", serializeLinks(source, uriInfo));

        return data.build();
    }

    public JsonObjectBuilder serializeIdentifier(E source) {
        JsonObjectBuilder data = Json.createObjectBuilder();

        data.add("type", getTypeName());
        data.add("id", toString(source.getId()));

        return data;
    }

    protected abstract JsonObject serializeAttributes(E source, UriInfo uriInfo);

    @SuppressWarnings("unused")
    protected JsonObjectBuilder serializeRelationships(E source, UriInfo uriInfo) {
        return null;
    }

    @SuppressWarnings("unused")
    protected JsonValue serializeLinks(E source, UriInfo uriInfo) {
        return JsonValue.NULL;
    }

    @SuppressWarnings("unused")
    protected JsonArray serializeIncluded(E source, UriInfo uriInfo) {
        return emptyJsonArray;
    }

    public E deserialize(JsonObject source, E target, boolean patch) {
        JsonObject data = source.getJsonObject("data");
        E deserialized;

        if (data != null) {
            JsonValue attrs = data.get("attributes");
            if (attrs != null && attrs.getValueType() == ValueType.OBJECT) {
                deserialized = deserializeAttributes((JsonObject) attrs, target, patch);

                JsonValue rels = data.get("relationships");

                if (rels != null && rels.getValueType() == ValueType.OBJECT) {
                    deserializeRelationships((JsonObject) rels, deserialized, patch);
                }
            } else {
                deserialized = target;
            }
        } else {
            deserialized = target;
        }

        return deserialized;
    }

    protected abstract E deserializeAttributes(JsonObject source, E target, boolean patch);

    @SuppressWarnings("unused")
    protected void deserializeRelationships(JsonObject source, E target, boolean patch) {
        return; //NO-OP
    }

    protected static JsonValue toString(final Object value) {
        if (value != null) {
            return new JsonString() {
                @Override
                public ValueType getValueType() {
                    return JsonValue.ValueType.STRING;
                }

                @Override
                public String getString() {
                    return value.toString();
                }

                @Override
                public CharSequence getChars() {
                    return value.toString();
                }

                @Override
                public boolean equals(Object other) {
                    return value.equals(other);
                }

                @Override
                public int hashCode() {
                    return value.hashCode();
                }
            };
        }
        return JsonValue.NULL;
    }

    protected static JsonValue toString(final Enum<?> value) {
        if (value != null) {
            return new JsonString() {
                @Override
                public ValueType getValueType() {
                    return JsonValue.ValueType.STRING;
                }

                @Override
                public String getString() {
                    return value.toString();
                }

                @Override
                public CharSequence getChars() {
                    return value.toString();
                }

                @Override
                public boolean equals(Object other) {
                    return value.equals(other);
                }

                @Override
                public int hashCode() {
                    return value.hashCode();
                }
            };
        }
        return JsonValue.NULL;
    }

    protected static JsonValue toNumber(final Number value) {
        if (value != null) {
            return new JsonNumber() {
                @Override
                public ValueType getValueType() {
                    return ValueType.NUMBER;
                }

                @Override
                public boolean isIntegral() {
                    return bigDecimalValue().scale() == 0;
                }

                @Override
                public int intValue() {
                    return value.intValue();
                }

                @Override
                public int intValueExact() {
                    return intValue();
                }

                @Override
                public long longValue() {
                    return value.longValue();
                }

                @Override
                public long longValueExact() {
                    return longValue();
                }

                @Override
                public BigInteger bigIntegerValue() {
                    return BigInteger.valueOf(value.longValue());
                }

                @Override
                public BigInteger bigIntegerValueExact() {
                    return bigIntegerValue();
                }

                @Override
                public double doubleValue() {
                    return value.doubleValue();
                }

                @Override
                public BigDecimal bigDecimalValue() {
                    return BigDecimal.valueOf(doubleValue());
                }

                @Override
                public String toString() {
                    return value.toString();
                }

                @Override
                public boolean equals(Object obj) {
                    return value.equals(obj);
                }

                @Override
                public int hashCode() {
                    return value.hashCode();
                }
            };
        }
        return JsonValue.NULL;
    }

    protected static JsonValue toString(Date value) {
        if (value != null) {
            Calendar dateTime = Calendar.getInstance();
            dateTime.setTime(value);
            dateTime.setTimeZone(TimeZone.getTimeZone("UTC"));
            return toString(DatatypeConverter.printDateTime(dateTime));
        }
        return JsonValue.NULL;
    }

    protected static Integer readInteger(JsonObject in, String name) {
        final JsonValue value = in.get(name);

        if (value instanceof JsonNumber) {
            return ((JsonNumber) value).intValue();
        } else if (value instanceof JsonString) {
            return Integer.valueOf(((JsonString) value).getString());
        }

        return null;
    }

    protected static Long readLong(JsonObject in, String name) {
        final JsonValue value = in.get(name);

        if (value instanceof JsonNumber) {
            return ((JsonNumber) value).longValue();
        } else if (value instanceof JsonString) {
            return Long.valueOf(((JsonString) value).getString());
        }

        return null;
    }

    protected static BigDecimal readBigDecimal(JsonObject in, String name) {
        final JsonValue value = in.get(name);

        if (value instanceof JsonNumber) {
            return ((JsonNumber) value).bigDecimalValue();
        } else if (value instanceof JsonString) {
            return new BigDecimal(((JsonString) value).getString());
        }

        return null;
    }

    protected static String readString(JsonObject in, String name) {
        return in.getString(name, null);
    }

    protected static Timestamp readTimestamp(JsonObject in, String name) {
        final String value = in.getString(name, null);
        final Timestamp result;

        if (value != null) {
            long millis = DatatypeConverter.parseDateTime(value).getTimeInMillis();
            Calendar normalized = Calendar.getInstance();
            normalized.setTimeInMillis(millis);
            normalized.set(Calendar.SECOND, 0);
            normalized.set(Calendar.MILLISECOND, 0);
            result = Timestamp.from(normalized.toInstant());
        } else {
            result = null;
        }

        return result;
    }

    protected static Date readDate(JsonObject in, String name) {
        final String value = in.getString(name, null);
        final Date result;

        if (value != null) {
            result = DatatypeConverter.parseDate(value).getTime();
        } else {
            result = null;
        }

        return result;
    }

    protected static Time readTime(JsonObject in, String name) {
        final String value = in.getString(name, null);
        final Time result;

        if (value != null) {
            long millis = DatatypeConverter.parseTime(value).getTimeInMillis();
            Calendar normalized = Calendar.getInstance();
            normalized.setTimeInMillis(millis);
            normalized.set(Calendar.SECOND, 0);
            normalized.set(Calendar.MILLISECOND, 0);
            result = new Time(normalized.getTimeInMillis());
        } else {
            result = null;
        }

        return result;
    }

    protected static void write(JsonGenerator out, String name, String value) {
        if (value != null) {
            out.write(name, value);
        } else {
            out.writeNull(name);
        }
    }

    protected static void write(JsonGenerator out, String name, Date value) {
        if (value != null) {
            Calendar dateTime = Calendar.getInstance();
            dateTime.setTime(value);
            out.write(name, DatatypeConverter.printDateTime(dateTime));
        } else {
            out.writeNull(name);
        }
    }

    protected static void write(JsonGenerator out, String name, Number value) {
        if (value == null) {
            out.writeNull(name);
        } else if (value instanceof BigDecimal) {
            out.write(name, (BigDecimal) value);
        } else if (value instanceof BigInteger) {
            out.write(name, (BigInteger) value);
        } else if (value instanceof Integer) {
            out.write(name, (Integer) value);
        } else if (value instanceof Long) {
            out.write(name, (Long) value);
        } else if (value instanceof Double) {
            out.write(name, (Double) value);
        }
    }
}
