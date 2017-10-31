package io.xlate.jsonapi.rs.boundary;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import io.xlate.jsonapi.rs.entity.AuditedEntity;

@Provider
@Produces("application/vnd.api+json")
public abstract class EntityListJsonWriter <T extends AuditedEntity> implements MessageBodyWriter<List<T>> {

    @Context
    protected UriInfo uriInfo;

    protected abstract AbstractJsonSerializer<T> getSerializer();

    private static Type getListEntryType(Type genericType) {
        ParameterizedType parameterizedType = (ParameterizedType) genericType;
        Type[] entryTypes = parameterizedType.getActualTypeArguments();

        if (entryTypes.length != 1) {
            return null;
        }

        return entryTypes[0];
    }

    @Override
    public boolean isWriteable(Class<?> type,
                               Type genericType,
                               Annotation[] annotations,
                               MediaType mediaType) {

        if (!List.class.isAssignableFrom(type)) {
            return false;
        }

        if (!(genericType instanceof ParameterizedType)) {
            return false;
        }

        Type entryType = getListEntryType(genericType);

        if (entryType != null) {
            return getSerializer().getType().equals(entryType);
        }
        return false;
    }

    @Override
    public long getSize(List<T> entities,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType) {

        return -1;
    }

    @Override
    public void writeTo(List<T> entities,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream)
                                                   throws WebApplicationException {

        AbstractJsonSerializer<T> serializer = getSerializer();
        JsonGenerator generator = Json.createGenerator(entityStream);
        generator.writeStartObject();
        generator.writeStartArray("data");

        for (T entity : entities) {
            generator.write(serializer.serialize(entity, uriInfo));
        }

        generator.writeEnd(); // data

        Map<String, String> written = new HashMap<>();

        for (T entity : entities) {
            JsonArray included = serializer.serializeIncluded(entity, uriInfo);

            if (included != null) {
                for (JsonValue entry : included) {
                    JsonObject obj = (JsonObject) entry;

                    if (written.isEmpty()) {
                        generator.writeStartArray("included");
                    }

                    String objId = obj.getString("id");
                    String objType = obj.getString("type");

                    if (!objType.equals(written.get(objId))) {
                        written.put(objId, objType);
                        generator.write(entry);
                    }
                }
            }
        }

        if (!written.isEmpty()) {
            generator.writeEnd(); // included
        }

        generator.writeEnd(); // container
        generator.close();
    }
}
