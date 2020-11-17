package io.xlate.jsonapi.rvp.internal.rs.boundary;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.core.Response.StatusType;

public class JsonApiError {

    public static final String DATA_ATTRIBUTES_POINTER = "/data/attributes";
    public static final String DATA_RELATIONSHIPS_POINTER = "/data/relationships";

    private final String status;
    private final String code;
    private final String title;
    private final String detail;
    private final Source source;

    public JsonApiError(String status, String code, String title, String detail, Source source) {
        this.status = status;
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.source = source;
    }

    public JsonApiError(StatusType status, String detail, Source source) {
        this(String.valueOf(status.getStatusCode()), null, status.getReasonPhrase(), detail, source);
    }

    public JsonApiError(StatusType status, String detail) {
        this(status, detail, null);
    }

    public JsonApiError(String status, String title, String detail, Source source) {
        this(status, null, title, detail, source);
    }

    public JsonApiError(String title, String detail, Source source) {
        this(null, null, title, detail, source);
    }

    public static String attributePointer(String attributeName) {
        return DATA_ATTRIBUTES_POINTER + '/' + attributeName;
    }

    public static String relationshipPointer(String relationshipName) {
        return DATA_RELATIONSHIPS_POINTER + '/' + relationshipName;
    }

    public JsonObject toJson() {
        JsonObjectBuilder builder = Json.createObjectBuilder();

        add(builder, "status", status);
        add(builder, "code", code);
        add(builder, "title", title);
        add(builder, "detail", detail);

        if (source != null) {
            builder.add("source", source.toJson());
        }

        return builder.build();
    }

    static void add(JsonObjectBuilder builder, String name, String value) {
        if (value != null) {
            builder.add(name, value);
        }
    }

    public String getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public Source getSource() {
        return source;
    }

    public static class Source {
        private final String pointer;
        private final String parameter;

        public static Source forPointer(String pointer) {
            return new Source(pointer, null);
        }

        public static Source forParameter(String parameter) {
            return new Source(null, parameter);
        }

        public Source(String pointer, String parameter) {
            this.pointer = pointer;
            this.parameter = parameter;
        }

        public JsonObject toJson() {
            JsonObjectBuilder builder = Json.createObjectBuilder();
            add(builder, "pointer", pointer);
            add(builder, "parameter", parameter);
            return builder.build();
        }

    }
}
