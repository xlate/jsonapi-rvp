package io.xlate.jsonapi.rvp;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class JsonApiResourceType<T> {

    public static final String CONFIGURATION_KEY = "io.xlate.jsonapi.rs.resourcetypes";

    private final String name;
    private final Class<T> klass;
    private final String exposedIdAttribute;
    private final Set<String> relationships;
    private final Map<String, Set<String>> uniqueTuples;
    private final Function<String, Object> idReader;
    private final String principalNamePath;

    public static <T> Builder<T> define(String name, Class<T> klass) {
        return new Builder<>(name, klass);
    }

    public static class Builder<T> {
        private final String name;
        private final Class<T> klass;
        private Set<String> relationships;
        private Map<String, Set<String>> uniqueTuples = new HashMap<>(3);
        private String exposedIdAttribute;
        private Function<String, Object> idReader;
        private String principalNamePath;

        private Builder(String name, Class<T> klass) {
            this.name = name;
            this.klass = klass;
        }

        public JsonApiResourceType<T> build() {
            return new JsonApiResourceType<>(name,
                                             klass,
                                             relationships,
                                             uniqueTuples,
                                             exposedIdAttribute,
                                             idReader,
                                             principalNamePath);
        }

        public Builder<T> relationships(String... relationships) {
            this.relationships = new HashSet<>(Arrays.asList(relationships));
            return this;
        }

        public Builder<T> unique(String name, String... attributes) {
            this.uniqueTuples.put(name, new HashSet<>(Arrays.asList(attributes)));
            return this;
        }

        public Builder<T> exposedIdAttribute(String attributeName, Function<String, Object> reader) {
            this.exposedIdAttribute = attributeName;
            this.idReader = reader;
            return this;
        }

        public Builder<T> principalNamePath(String path) {
            this.principalNamePath = path;
            return this;
        }
    }

    private JsonApiResourceType(String name,
            Class<T> klass,
            Set<String> relationships,
            Map<String, Set<String>> uniqueTuples,
            String exposedIdAttribute,
            Function<String, Object> idReader,
            String principalNamePath) {
        super();
        this.name = name;
        this.klass = klass;

        if (relationships != null) {
            this.relationships = Collections.unmodifiableSet(relationships);
        } else {
            this.relationships = Collections.emptySet();
        }

        this.uniqueTuples = uniqueTuples;
        this.exposedIdAttribute = exposedIdAttribute;
        this.principalNamePath = principalNamePath;

        if (idReader != null) {
            this.idReader = idReader;
        } else {
            this.idReader = id -> id;
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        JsonApiResourceType<?> other = (JsonApiResourceType<?>) obj;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        return true;
    }

    public String getName() {
        return name;
    }

    public Class<?> getResourceClass() {
        return klass;
    }

    public Set<String> getRelationships() {
        return relationships;
    }

    public Map<String, Set<String>> getUniqueTuples() {
        return uniqueTuples;
    }

    public String getExposedIdAttribute() {
        return exposedIdAttribute;
    }

    public Function<String, Object> getIdReader() {
        return idReader;
    }

    public String getPrincipalNamePath() {
        return principalNamePath;
    }
}
