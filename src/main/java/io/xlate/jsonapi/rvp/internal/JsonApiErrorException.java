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
package io.xlate.jsonapi.rvp.internal;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;
import javax.ws.rs.core.Response.StatusType;

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
