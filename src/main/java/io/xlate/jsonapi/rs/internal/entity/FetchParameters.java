package io.xlate.jsonapi.rs.internal.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;

import io.xlate.jsonapi.rs.internal.boundary.ResourceObjectReader;
import io.xlate.jsonapi.rs.internal.boundary.ValidJsonApiUriParameters;

@ValidJsonApiUriParameters
public class FetchParameters {

    public static final String PARAM_INCLUDE = "include";
    public static final String PARAM_SORT = "sort";
    public static final String PARAM_PAGE_OFFSET = "page[offset]";
    public static final String PARAM_PAGE_LIMIT = "page[limit]";

    private final EntityMeta entityMeta;
    private final String id;
    private final UriInfo uriInfo;
    private boolean uriProcessed = false;

    private List<String> include = new ArrayList<>();
    private List<String> count = new ArrayList<>();
    private List<String> sort = new ArrayList<>();
    private Integer pageOffset = null;
    private Integer pageLimit;
    private Map<String, List<String>> fields = new HashMap<>();

    public FetchParameters(EntityMeta entityMeta, String id, UriInfo uriInfo) {
        super();
        this.entityMeta = entityMeta;
        this.id = id;
        this.uriInfo = uriInfo;
    }

    private void processUri() {
        if (uriProcessed) {
            return;
        }

        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();

        params.entrySet()
              .stream()
              .filter(p -> p.getKey().matches("fields\\[[^]]+?\\]"))
              .forEach(p -> {
                  Pattern fieldp = Pattern.compile("fields\\[([^]]+?)\\]");
                  Matcher fieldm = fieldp.matcher(p.getKey());
                  fieldm.find();
                  for (String fieldl : p.getValue()) {
                      for (String fieldName : fieldl.split(",")) {
                          this.addField(fieldm.group(1), ResourceObjectReader.toAttributeName(fieldName));
                      }
                  }
              });

        if (params.containsKey(PARAM_INCLUDE)) {
            String includeParam = params.getFirst(PARAM_INCLUDE);

            for (String include : includeParam.split(",")) {
                String attribute = ResourceObjectReader.toAttributeName(include);

                this.include.add(attribute);
                this.count.remove(attribute);
            }
        }

        if (params.containsKey(PARAM_SORT)) {
            String sortParam = params.getFirst(PARAM_SORT);

            for (String sort : sortParam.split(",")) {
                boolean descending = sort.startsWith("-");
                String attribute = ResourceObjectReader.toAttributeName(sort.substring(descending ? 1 : 0));
                this.sort.add(descending ? '-' + attribute : attribute);
            }
        }

        if (params.containsKey(PARAM_PAGE_OFFSET)) {
            this.pageOffset = Integer.parseInt(params.getFirst(PARAM_PAGE_OFFSET));
        }

        if (params.containsKey(PARAM_PAGE_LIMIT)) {
            this.pageLimit = Integer.parseInt(params.getFirst(PARAM_PAGE_LIMIT));
        }

        uriProcessed = true;
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

    public Integer getPageOffset() {
        processUri();
        return pageOffset;
    }

    public Integer getPageLimit() {
        processUri();
        return pageLimit;
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
