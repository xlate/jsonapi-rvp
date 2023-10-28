package io.xlate.jsonapi.rvp.internal;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonReader;
import jakarta.ws.rs.core.Response.StatusType;

public class JsonApiErrorException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final StatusType status;
    private final String title;
    private final String detail;
    private JsonArray errors; // NOSONAR: non-final for de-serialization

    public JsonApiErrorException(StatusType status, JsonArray errors) {
        this.status = status;
        this.title = null;
        this.detail = null;
        this.errors = errors;
    }

    public JsonApiErrorException(StatusType status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.errors = null;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeBoolean(errors != null);

        if (errors != null) {
            out.writeUTF(errors.toString());
        }
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();

        if (in.readBoolean()) {
            try (JsonReader reader = Json.createReader(in)) {
                this.errors = reader.readArray();
            }
        }
    }

    public StatusType getStatus() {
        return status;
    }

    public JsonArray getErrors() {
        return errors;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

}
