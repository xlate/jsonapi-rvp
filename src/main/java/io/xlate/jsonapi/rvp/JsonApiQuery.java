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
package io.xlate.jsonapi.rvp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.validation.boundary.ValidJsonApiQuery;

@ValidJsonApiQuery
public class JsonApiQuery {

    public static final String PARAM_INCLUDE = "include";
    public static final String PARAM_SORT = "sort";

    public static final String PARAM_PAGE_OFFSET = "page[offset]";
    public static final String PARAM_PAGE_LIMIT = "page[limit]";

    public static final String PARAM_PAGE_NUMBER = "page[number]";
    public static final String PARAM_PAGE_SIZE = "page[size]";

    private final EntityMetamodel model;
    private final EntityMeta entityMeta;
    private final String id;
    private final UriInfo uriInfo;
    private boolean uriProcessed = false;

    private Map<String, List<String>> fields = new HashMap<>();
    private Map<String, String> filters = new HashMap<>();

    private List<String> include = new ArrayList<>();
    private List<String> count = new ArrayList<>();
    private List<String> sort = new ArrayList<>();

    private Integer firstResult = null;
    private Integer maxResults;

    public JsonApiQuery(EntityMetamodel model, EntityMeta entityMeta, String id, UriInfo uriInfo) {
        super();
        this.model = model;
        this.entityMeta = entityMeta;
        this.id = id;
        this.uriInfo = uriInfo;
    }

    private void processUri() {
        if (uriProcessed) {
            return;
        }

        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();

        processFields(params);
        processFilters(params);
        processPaging(params);

        if (params.containsKey(PARAM_INCLUDE)) {
            for (String attribute : params.getFirst(PARAM_INCLUDE).split(",")) {
                this.include.add(attribute);
                this.count.remove(attribute);
            }
        }

        if (params.containsKey(PARAM_SORT)) {
            this.sort.addAll(Arrays.asList(params.getFirst(PARAM_SORT).split(",")));
        }

        uriProcessed = true;
    }

    void processFields(MultivaluedMap<String, String> params) {
        Pattern fieldp = Pattern.compile("fields\\[([^]]+?)\\]");

        params.entrySet()
              .stream()
              .filter(p -> p.getKey().matches("fields\\[[^]]+?\\]"))
              .forEach(p -> {
                  Matcher fieldm = fieldp.matcher(p.getKey());
                  fieldm.find();
                  for (String fieldl : p.getValue()) {
                      for (String fieldName : fieldl.split(",")) {
                          addField(this.fields, fieldm.group(1), fieldName);
                      }
                  }
              });
    }

    void processFilters(MultivaluedMap<String, String> params) {
        Pattern filterp = Pattern.compile("filter\\[([^]]+?)\\]");

        params.entrySet()
              .stream()
              .filter(p -> p.getKey().matches("filter\\[[^]]+?\\]"))
              .forEach(p -> {
                  Matcher filterm = filterp.matcher(p.getKey());
                  filterm.find();
                  addFilter(this.filters, replaceIdentifier(filterm.group(1)), p.getValue().get(0));
              });
    }

    String replaceIdentifier(String fieldPath) {
        boolean validFilter = true;
        String[] elements = fieldPath.split("\\.");
        EntityMeta meta = entityMeta;

        for (int i = 0; i < elements.length && validFilter; i++) {
            if (i + 1 == elements.length) {
                String attributeName = elements[i];

                if ("id".equals(attributeName)) {
                    elements[i] = entityMeta.getExposedIdAttribute().getName();
                    fieldPath = String.join(".", elements);
                }
            } else {
                String relationshipName = elements[i];

                if (meta.isRelatedTo(relationshipName)) {
                    meta = model.getEntityMeta(meta.getRelatedEntityClass(relationshipName));
                } else {
                    validFilter = false;
                }
            }
        }

        return fieldPath;
    }

    void processPaging(MultivaluedMap<String, String> params) {
        if (params.containsKey(PARAM_PAGE_OFFSET)) {
            this.firstResult = tryParseInt(params.getFirst(PARAM_PAGE_OFFSET), 0);

            if (params.containsKey(PARAM_PAGE_LIMIT)) {
                int limit = tryParseInt(params.getFirst(PARAM_PAGE_LIMIT), this.firstResult + 10);
                this.maxResults = limit - this.firstResult;
            } else {
                this.maxResults = 10;
            }
        } else if (params.containsKey(PARAM_PAGE_NUMBER)) {
            int pageNumber = tryParseInt(params.getFirst(PARAM_PAGE_NUMBER), 1);

            if (params.containsKey(PARAM_PAGE_SIZE)) {
                this.maxResults = tryParseInt(params.getFirst(PARAM_PAGE_SIZE), 10);
            } else {
                this.maxResults = 10;
            }

            this.firstResult = (pageNumber - 1 ) + this.maxResults;
        } else if (params.containsKey(PARAM_PAGE_LIMIT)) {
            this.maxResults = tryParseInt(params.getFirst(PARAM_PAGE_LIMIT), 10);
        } else if (params.containsKey(PARAM_PAGE_SIZE)) {
            this.maxResults = tryParseInt(params.getFirst(PARAM_PAGE_SIZE), 10);
        } else {
            this.firstResult = 0;
        }
    }

    Integer tryParseInt(String value, Integer defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public EntityMetamodel getModel() {
        return model;
    }

    public EntityMeta getEntityMeta() {
        return entityMeta;
    }

    public String getId() {
        return id;
    }

    public UriInfo getUriInfo() {
        return uriInfo;
    }

    public Map<String, String> getFilters() {
        processUri();
        return filters;
    }

    public List<String> getInclude() {
        processUri();
        return include;
    }

    public List<String> getCount() {
        processUri();
        return count;
    }

    public List<String> getSort() {
        processUri();
        return sort;
    }

    public Integer getFirstResult() {
        processUri();
        return firstResult;
    }

    public Integer getMaxResults() {
        processUri();
        return maxResults;
    }

    public void addField(String resourceType, String fieldName) {
        processUri();
        addField(this.fields, resourceType, fieldName);
    }

    public static void addField(Map<String, List<String>> fields, String resourceType, String fieldName) {
        if (!fields.containsKey(resourceType)) {
            fields.put(resourceType, new ArrayList<String>());
        }
        fields.get(resourceType).add(fieldName);
    }

    public static void addFilter(Map<String, String> filters, String fieldName, String fieldValue) {
        filters.put(fieldName, fieldValue);
    }

    public boolean includeField(String resourceType, String fieldName) {
        processUri();
        return includeField(this.fields, resourceType, fieldName);
    }

    public static boolean includeField(Map<String, List<String>> fields, String resourceType, String fieldName) {
        if (fields.containsKey(resourceType)) {
            if (fields.get(resourceType).isEmpty()) {
                return true;
            }
            return fields.get(resourceType).contains(fieldName);
        }
        return true;
    }
}
