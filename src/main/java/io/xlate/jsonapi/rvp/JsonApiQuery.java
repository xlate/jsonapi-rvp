package io.xlate.jsonapi.rvp;

import java.util.List;
import java.util.Map;

public interface JsonApiQuery {

    Map<String, List<String>> getFields();

    Map<String, String> getFilters();

    List<String> getInclude();

    List<String> getSort();

    Integer getFirstResult();

    Integer getMaxResults();

    boolean includeField(String resourceType, String fieldName);

}
