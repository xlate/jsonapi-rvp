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

import javax.json.JsonArray;
import javax.ws.rs.core.Response.Status;

public class JsonApiClientErrorException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Status status;
    private final JsonArray errors;
    private final String title;
    private final String detail;

    public JsonApiClientErrorException(Status status, JsonArray errors) {
        this.status = status;
        this.errors = errors;
        this.title = null;
        this.detail = null;
    }

    public JsonApiClientErrorException(Status status, String title, String detail) {
        this.status = status;
        this.errors = null;
        this.title = title;
        this.detail = detail;
    }

    public Status getStatus() {
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
