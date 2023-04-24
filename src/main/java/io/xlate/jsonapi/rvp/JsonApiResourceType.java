package io.xlate.jsonapi.rvp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;

public class JsonApiResourceType<T> {

    public static final String CONFIGURATION_KEY = "io.xlate.jsonapi.rs.resourcetypes";
    static final Set<Class<?>> ALL_METHODS = Set.of(GET.class, POST.class, PUT.class, PATCH.class, DELETE.class);

    private final String name;
    private final Class<T> klass;
    private final Set<Class<?>> methods;
    private final String exposedIdAttribute;

    private final Set<String> attributes;
    private final Map<String, Function<String, Object>> readers;

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
        private final Set<Class<?>> methods = new HashSet<>();

        private Set<String> attributes;
        private Map<String, Function<String, Object>> readers = new HashMap<>(5);

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
                                             methods,
                                             attributes,
                                             readers,
                                             relationships,
                                             uniqueTuples,
                                             exposedIdAttribute,
                                             idReader,
                                             principalNamePath);
        }

        public Builder<T> methods(Class<?>... methods) {
            for (Class<?> method : methods) {
                if (ALL_METHODS.stream().noneMatch(method::equals)) {
                    throw new IllegalArgumentException("Unsupported method: " + method);
                }
                this.methods.add(method);
            }
            return this;
        }

        public Builder<T> attributes(String... attributes) {
            this.attributes = new HashSet<>(Arrays.asList(attributes));
            return this;
        }

        /**
         * Provide a function to convert string-based attributes to a custom object
         *
         * @param attributeName Name of the attribute using the reader
         * @param reader Function to parse a string JSON attribute to an object value
         * @return the builder
         */
        public Builder<T> reader(String attributeName, Function<String, Object> reader) {
            this.readers.put(attributeName, reader);
            return this;
        }

        public Builder<T> relationships(String... relationships) {
            this.relationships = new HashSet<>(Arrays.asList(relationships));
            return this;
        }

        public Builder<T> unique(String name, Set<String> attributes) {
            this.uniqueTuples.put(name, Set.copyOf(attributes));
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

    @SuppressWarnings("java:S107")
    private JsonApiResourceType(String name,
            Class<T> klass,
            Set<Class<?>> methods,
            Set<String> attributes,
            Map<String, Function<String, Object>> readers,
            Set<String> relationships,
            Map<String, Set<String>> uniqueTuples,
            String exposedIdAttribute,
            Function<String, Object> idReader,
            String principalNamePath) {
        super();
        this.name = name;
        this.klass = klass;

        if (methods.isEmpty()) {
            this.methods = ALL_METHODS;
        } else {
            this.methods = Set.copyOf(methods);
        }

        if (attributes != null) {
            this.attributes = Set.copyOf(attributes);
        } else {
            this.attributes = Set.of(); // Empty
        }

        this.readers = Map.copyOf(readers);

        if (relationships != null) {
            this.relationships = Set.copyOf(relationships);
        } else {
            this.relationships = Set.of(); // Empty
        }

        this.uniqueTuples = Map.copyOf(uniqueTuples);
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

    public Set<Class<?>> getMethods() {
        return methods;
    }

    public Set<String> getAttributes() {
        return attributes;
    }

    public Map<String, Function<String, Object>> getReaders() {
        return readers;
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
