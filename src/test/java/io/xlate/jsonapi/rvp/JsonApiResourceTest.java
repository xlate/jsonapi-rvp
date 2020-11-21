package io.xlate.jsonapi.rvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.inject.Instance;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.validation.Validation;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.Mockito;
import org.skyscreamer.jsonassert.JSONAssert;

import io.xlate.jsonapi.rvp.internal.DefaultJsonApiHandler;
import io.xlate.jsonapi.rvp.internal.validation.boundary.TransactionalValidator;
import io.xlate.jsonapi.rvp.test.entity.Comment;
import io.xlate.jsonapi.rvp.test.entity.Post;
import io.xlate.jsonapi.rvp.test.entity.ReadOnlyCode;

class JsonApiResourceTest {

    @Path("/test")
    static class ApiImpl extends JsonApiResource {
    }

    static EntityManagerFactory emf;

    EntityManager em;
    JsonApiResource target;
    JsonApiHandler<?> defaultHandler = new DefaultJsonApiHandler();

    @BeforeAll
    static void initialize() {
        emf = Persistence.createEntityManagerFactory("test");
    }

    Iterator<JsonApiHandler<?>> handlerIterator() {
        List<JsonApiHandler<?>> handlers = Arrays.asList(defaultHandler);
        return handlers.iterator();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        em = emf.createEntityManager();
        target = new ApiImpl();
        target.persistenceContext = em;
        target.validator = Validation.buildDefaultValidatorFactory().getValidator();
        target.handlers = Mockito.mock(Instance.class);
        target.request = Mockito.mock(Request.class);
        target.security = Mockito.mock(SecurityContext.class);
        target.txValidator = new TransactionalValidator(target.validator);

        Mockito.when(target.handlers.iterator()).thenReturn(handlerIterator());

        Set<JsonApiResourceType<?>> resourceTypes = new HashSet<>();
        resourceTypes.add(JsonApiResourceType.define("posts", Post.class).build());
        resourceTypes.add(JsonApiResourceType.define("comments", Comment.class).build());
        resourceTypes.add(JsonApiResourceType.define("readonly-codes", ReadOnlyCode.class).methods(GET.class).build());
        target.initialize(resourceTypes);
    }

    @AfterEach
    void tearDown() {
        var tx = em.getTransaction();
        tx.begin();
        em.createQuery("DELETE Comment").executeUpdate();
        em.createQuery("DELETE Post").executeUpdate();
        tx.commit();
        em.close();
    }

    void executeDml(String jsonDml) {
        if (!jsonDml.isBlank()) {
            JsonArray commands = Json.createReader(new StringReader(jsonDml)).readArray();

            for (JsonValue entry : commands) {
                JsonObject command = entry.asJsonObject();
                em.createNativeQuery(command.getString("sql")).executeUpdate();
            }
        }
    }

    void assertResponseEquals(int expectedStatus, int actualStatus, String expectedEntity, String actualEntity) throws JSONException {
        try {
            assertEquals(expectedStatus, actualStatus);
            JSONAssert.assertEquals(expectedEntity, actualEntity, true);
        } catch (Throwable t) {
            Map<String, Object> map = new HashMap<>();
            map.put(JsonGenerator.PRETTY_PRINTING, true);
            JsonWriterFactory writerFactory = Json.createWriterFactory(map);
            JsonWriter jsonWriter = writerFactory.createWriter(System.out);
            jsonWriter.writeObject(Json.createReader(new StringReader(actualEntity)).readObject());
            jsonWriter.close();
            throw t;
        }
    }

    @ParameterizedTest
    @CsvFileSource(delimiter = '|', lineSeparator = "@\n", files = "src/test/resources/create-post.txt")
    void testCreatePost(String title,
                        String jsonDml,
                        String requestUri,
                        String resourceType,
                        String requestBody,
                        int expectedStatus,
                        String expectedResponse)
            throws JSONException {

        Mockito.when(target.request.getMethod()).thenReturn("POST");
        target.uriInfo = new ResteasyUriInfo(requestUri, "/");

        em.getTransaction().begin();
        executeDml(jsonDml);
        Response response = target.create(resourceType, Json.createReader(new StringReader(requestBody.replace('\'', '"'))).readObject());
        em.getTransaction().commit();

        assertNotNull(response);

        String responseEntity = String.valueOf(response.getEntity());
        assertResponseEquals(expectedStatus, response.getStatus(), expectedResponse, responseEntity);
    }

    @ParameterizedTest
    @CsvFileSource(delimiter = '|', lineSeparator = "@\n", files = "src/test/resources/index-get.txt")
    void testIndexGet(String title,
                      String jsonDml,
                      String requestUri,
                      String resourceType,
                      int expectedStatus,
                      String expectedResponse)
            throws JSONException {

        Mockito.when(target.request.getMethod()).thenReturn("GET");
        target.uriInfo = new ResteasyUriInfo(requestUri, "/");

        em.getTransaction().begin();
        executeDml(jsonDml);
        em.getTransaction().commit();

        Response response = target.index(resourceType);
        assertNotNull(response);

        String responseEntity = String.valueOf(response.getEntity());
        assertResponseEquals(expectedStatus, response.getStatus(), expectedResponse, responseEntity);
    }

}
