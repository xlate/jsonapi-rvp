package io.xlate.jsonapi.rvp.internal.rs.entity;

import static io.xlate.jsonapi.rvp.internal.validation.boundary.JsonApiUriQueryValidator.getRelatedEntityMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rvp.JsonApiQuery;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMeta;
import io.xlate.jsonapi.rvp.internal.persistence.entity.EntityMetamodel;
import io.xlate.jsonapi.rvp.internal.validation.boundary.ValidJsonApiQuery;

@ValidJsonApiQuery
public class InternalQuery implements JsonApiQuery {

    public static final String PARAM_INCLUDE = "include";
    public static final String PARAM_SORT = "sort";

    public static final String PARAM_PAGE_OFFSET = "page[offset]";
    public static final String PARAM_PAGE_LIMIT = "page[limit]";

    public static final String PARAM_PAGE_NUMBER = "page[number]";
    public static final String PARAM_PAGE_SIZE = "page[size]";

    private static final Pattern PATTERN_FIELDS = Pattern.compile("fields\\[([^]]+?)\\]");
    private static final Pattern PATTERN_FILTER = Pattern.compile("filter\\[([^]]+?)\\]");

    private final EntityMetamodel model;
    private final EntityMeta entityMeta;
    private final String id;
    private final String relationshipName;
    private final UriInfo uriInfo;
    private boolean uriProcessed = false;

    private Map<String, List<String>> fields = new HashMap<>();
    private Map<String, String> filters = new HashMap<>();

    private List<String> include = new ArrayList<>();
    private List<String> count = new ArrayList<>();
    private List<String> sort = new ArrayList<>();

    private Integer firstResult = null;
    private Integer maxResults;

    public InternalQuery(EntityMetamodel model, EntityMeta entityMeta, String id, String relationshipName, UriInfo uriInfo) {
        super();
        this.model = model;
        this.entityMeta = entityMeta;
        this.id = id;
        this.relationshipName = relationshipName;
        this.uriInfo = uriInfo;
    }

    private void processUri() {
        if (uriProcessed) {
            return;
        }

        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();

        processParamMatches(params, PATTERN_FIELDS, this::processFields);
        processParamMatches(params, PATTERN_FILTER, this::processFilters);

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

    void processParamMatches(MultivaluedMap<String, String> params,
                             Pattern fieldPattern,
                             Consumer<Map.Entry<Matcher, List<String>>> handler) {

        params.entrySet()
              .stream()
              .map(entry -> Map.entry(fieldPattern.matcher(entry.getKey()), entry.getValue()))
              .filter(entry -> entry.getKey().matches())
              .forEach(handler);
    }

    void processFields(Map.Entry<Matcher, List<String>> fields) {
        final String resourceType = fields.getKey().group(1);

        fields.getValue()
              .stream()
              .flatMap(field -> Arrays.stream(field.split(",")))
              .forEach(fieldName -> addField(this.fields, resourceType, fieldName));
    }

    void processFilters(Map.Entry<Matcher, List<String>> filters) {
        final String filterField = replaceIdentifier(filters.getKey().group(1));

        filters.getValue()
               .forEach(value -> addFilter(this.filters, filterField, value));
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
                validFilter = (meta = getRelatedEntityMeta(model, meta, elements[i])) != null;
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
            final int pageNumber = tryParseInt(params.getFirst(PARAM_PAGE_NUMBER), 1);
            final int pageSize;

            if (params.containsKey(PARAM_PAGE_SIZE)) {
                pageSize = tryParseInt(params.getFirst(PARAM_PAGE_SIZE), 10);
            } else {
                pageSize = 10;
            }

            this.firstResult = ((pageNumber - 1) * pageSize);
            this.maxResults = pageSize;
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

    public String getRelationshipName() {
        return relationshipName;
    }

    public UriInfo getUriInfo() {
        return uriInfo;
    }

    @Override
    public Map<String, List<String>> getFields() {
        processUri();
        // TODO: Ensure the values are unmodifiable after creation
        return Collections.unmodifiableMap(this.fields);
    }

    @Override
    public Map<String, String> getFilters() {
        processUri();
        return Collections.unmodifiableMap(this.filters);
    }

    @Override
    public List<String> getInclude() {
        processUri();
        return Collections.unmodifiableList(this.include);
    }

    public List<String> getCount() {
        processUri();
        return count;
    }

    @Override
    public List<String> getSort() {
        processUri();
        return Collections.unmodifiableList(this.sort);
    }

    @Override
    public Integer getFirstResult() {
        processUri();
        return firstResult;
    }

    @Override
    public Integer getMaxResults() {
        processUri();
        return maxResults;
    }

    public void addField(String resourceType, String fieldName) {
        processUri();
        addField(this.fields, resourceType, fieldName);
    }

    private static void addField(Map<String, List<String>> fields, String resourceType, String fieldName) {
        fields.computeIfAbsent(resourceType, k -> new ArrayList<>()).add(fieldName);
    }

    private static void addFilter(Map<String, String> filters, String fieldName, String fieldValue) {
        filters.put(fieldName, fieldValue);
    }

    public boolean includeField(String resourceType, String fieldName) {
        processUri();
        return includeField(this.fields, resourceType, fieldName);
    }

    private static boolean includeField(Map<String, List<String>> fields, String resourceType, String fieldName) {
        if (fields.containsKey(resourceType)) {
            if (fields.get(resourceType).isEmpty()) {
                return true;
            }
            return fields.get(resourceType).contains(fieldName);
        }
        return true;
    }
}
