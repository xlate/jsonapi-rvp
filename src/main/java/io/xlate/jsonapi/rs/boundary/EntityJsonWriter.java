package io.xlate.jsonapi.rs.boundary;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import javax.json.Json;
import javax.json.JsonArray;
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
public abstract class EntityJsonWriter <T extends AuditedEntity> implements MessageBodyWriter<T> {

    @Context
    protected UriInfo uriInfo;

    protected abstract AbstractJsonSerializer<T> getSerializer();

    @Override
    public boolean isWriteable(Class<?> type,
                               Type genericType,
                               Annotation[] annotations,
                               MediaType mediaType) {

        return getSerializer().getType().equals(type);
    }

    @Override
    public long getSize(T entity,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType) {

        return -1;
    }

    @Override
    public void writeTo(T entity,
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
        generator.write("data", serializer.serialize(entity, uriInfo));

        final JsonArray included = serializer.serializeIncluded(entity, uriInfo);

        if (included != null) {
            generator.write("included", included);
        }

        generator.writeEnd(); // container
        generator.close();
    }
}
