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
package io.xlate.jsonapi.rvp.internal.rs.entity;

import jakarta.json.JsonObject;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.validation.boundary.ValidJsonApiRequest;

@ValidJsonApiRequest
public class JsonApiRequest {

    private final String requestMethod;
    private final EntityMetamodel model;
    private final EntityMeta meta;
    private final String id;
    private final JsonObject document;

    public JsonApiRequest(String requestMethod, EntityMetamodel model, EntityMeta meta, String id, JsonObject document) {
        super();
        this.requestMethod = requestMethod;
        this.model = model;
        this.meta = meta;
        this.id = id;
        this.document = document;
    }

    public boolean isRequestMethod(String requestMethod) {
        return this.requestMethod.equals(requestMethod);
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public EntityMetamodel getModel() {
        return model;
    }

    public EntityMeta getEntityMeta() {
        return meta;
    }

    public String getId() {
        return id;
    }

    public JsonObject getDocument() {
        return document;
    }
}
