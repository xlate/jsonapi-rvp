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
