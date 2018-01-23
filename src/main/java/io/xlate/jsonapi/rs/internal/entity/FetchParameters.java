package io.xlate.jsonapi.rs.internal.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FetchParameters {

    private List<String> include = new ArrayList<>();
    private List<String> count = new ArrayList<>();
    private List<String> sort = new ArrayList<>();
    private Integer pageOffset = null;
    private Integer pageLimit;
    private Map<String, List<String>> fields = new HashMap<>();

    public List<String> getInclude() {
        return include;
    }

    public List<String> getCount() {
        return count;
    }

    public List<String> getSort() {
        return sort;
    }

    public Integer getPageOffset() {
        return pageOffset;
    }

    public void setPageOffset(Integer pageOffset) {
        this.pageOffset = pageOffset;
    }

    public Integer getPageLimit() {
        return pageLimit;
    }

    public void setPageLimit(Integer pageLimit) {
        this.pageLimit = pageLimit;
    }

    public void addField(String resourceType, String fieldName) {
        if (!fields.containsKey(resourceType)) {
            fields.put(resourceType, new ArrayList<String>());
        }
        fields.get(resourceType).add(fieldName);
    }

    public boolean includeField(String resourceType, String fieldName) {
        if (fields.containsKey(resourceType)) {
            if (fields.get(resourceType).isEmpty()) {
                return true;
            }
            return fields.get(resourceType).contains(fieldName);
        }
        return true;
    }
}
