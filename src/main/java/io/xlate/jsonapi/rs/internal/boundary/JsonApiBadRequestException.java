package io.xlate.jsonapi.rs.internal.boundary;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

public class JsonApiBadRequestException extends WebApplicationException {
    private static final long serialVersionUID = 1L;

    public JsonApiBadRequestException(JsonObject error) {
        super(Response.status(Status.BAD_REQUEST)
                      .entity(Json.createObjectBuilder()
                                  .add("errors", Json.createArrayBuilder().add(error))
                                  .build())
                      .build());
    }

    public JsonApiBadRequestException(JsonArray errors) {
        super(Response.status(Status.BAD_REQUEST)
                      .entity(Json.createObjectBuilder()
                                  .add("errors", errors)
                                  .build())
                      .build());
    }

    public JsonApiBadRequestException(String title, String detail) {
        this(Json.createObjectBuilder().add("title", title)
                                       .add("detail", detail)
                                       .build());
    }
}
