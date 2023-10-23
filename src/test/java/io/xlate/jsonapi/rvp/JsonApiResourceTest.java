package io.xlate.jsonapi.rvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.validation.Validation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import io.xlate.jsonapi.rvp.internal.DefaultJsonApiHandler;
import io.xlate.jsonapi.rvp.internal.validation.boundary.TransactionalValidator;
import io.xlate.jsonapi.rvp.test.entity.Author;
import io.xlate.jsonapi.rvp.test.entity.Comment;
import io.xlate.jsonapi.rvp.test.entity.Post;
import io.xlate.jsonapi.rvp.test.entity.ReadOnlyCode;
import io.xlate.jsonapi.rvp.test.entity.TypeModel;

class JsonApiResourceTest {

    @Path("/test")
    static class ApiImpl extends JsonApiResource {
    }

    EntityManagerFactory emf;
    EntityManager em;
    JsonApiResource target;
    JsonApiHandler<?> defaultHandler = new DefaultJsonApiHandler();

    Iterator<JsonApiHandler<?>> handlerIterator() {
        List<JsonApiHandler<?>> handlers = Arrays.asList(defaultHandler);
        return handlers.iterator();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        emf = Persistence.createEntityManagerFactory("test");
        em = emf.createEntityManager();
        target = new ApiImpl();
        target.persistenceContext = em;
        target.validator = Validation.buildDefaultValidatorFactory().getValidator();
        target.handlers = Mockito.mock(Instance.class);
        target.request = Mockito.mock(Request.class);
        target.security = Mockito.mock(SecurityContext.class);
        target.txValidator = new TransactionalValidator();
        target.txValidator.setValidator(target.validator);

        Mockito.when(target.handlers.iterator()).thenReturn(handlerIterator());

        Set<JsonApiResourceType<?>> resourceTypes = new HashSet<>();
        resourceTypes.add(JsonApiResourceType.define("authors", Author.class)
                                             .build());
        resourceTypes.add(JsonApiResourceType.define("posts", Post.class)
                                             .exposedIdAttribute("id", Integer::valueOf)
                                             .reader("title", String::valueOf)
                                             .build());
        resourceTypes.add(JsonApiResourceType.define("comments", Comment.class)
                                             .build());
        resourceTypes.add(JsonApiResourceType.define("readonly-codes", ReadOnlyCode.class)
                                             .methods(GET.class)
                                             .build());
        resourceTypes.add(JsonApiResourceType.define("type-models", TypeModel.class)
                                             .methods(GET.class, POST.class, PATCH.class)
                                             .build());
        target.initialize(resourceTypes);
    }

    @AfterEach
    void tearDown() {
        em.close();
        emf.close();
    }

    void executeDml(String jsonDml) {
        if (!jsonDml.isBlank()) {
            JsonArray commands = Json.createReader(new StringReader(jsonDml)).readArray();
            var tx = em.getTransaction();
            tx.begin();

            for (JsonValue entry : commands) {
                JsonObject command = entry.asJsonObject();
                em.createNativeQuery(command.getString("sql")).executeUpdate();
            }

            tx.commit();
        }
    }

    JsonObject readObject(String requestBody) {
        return Json.createReader(new StringReader(requestBody.replace('\'', '"'))).readObject();
    }

    void assertResponseEquals(int expectedStatus, int actualStatus, String expectedEntity, String actualEntity) throws JSONException {
        try {
            assertEquals(expectedStatus, actualStatus);
            if (expectedEntity == null || expectedEntity.isBlank()) {
                assertEquals(expectedEntity, actualEntity);
            } else {
                JSONAssert.assertEquals(expectedEntity, actualEntity, JSONCompareMode.NON_EXTENSIBLE);
            }
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

    void testResourceMethod(String jsonDml,
                            String requestUri,
                            String requestMethod,
                            int expectedStatus,
                            String expectedResponse,
                            Supplier<Response> responseSupplier)
            throws JSONException {

        executeDml(jsonDml);

        Mockito.when(target.request.getMethod()).thenReturn(requestMethod);
        target.uriInfo = new ResteasyUriInfo(requestUri, "/");
        Response response;

        var tx = em.getTransaction();

        try {
            tx.begin();
            response = responseSupplier.get();
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }

        assertNotNull(response);

        Object entityObject = response.getEntity();
        String responseEntity = entityObject != null ? String.valueOf(entityObject) : null;
        assertResponseEquals(expectedStatus, response.getStatus(), expectedResponse, responseEntity);
    }

    @ParameterizedTest
    @CsvFileSource(
        delimiter = '|',
        lineSeparator = "@\n",
        files = {
            "src/test/resources/create-post.txt",
            "src/test/resources/create-post-invalid.txt" })
    void testCreatePost(String title,
                        String jsonDml,
                        String requestUri,
                        String resourceType,
                        String requestBody,
                        int expectedStatus,
                        String expectedResponse)
            throws JSONException {

        testResourceMethod(jsonDml,
                           requestUri,
                           "POST",
                           expectedStatus,
                           expectedResponse,
                           () -> target.create(resourceType, readObject(requestBody)));
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

        testResourceMethod(jsonDml,
                           requestUri,
                           "GET",
                           expectedStatus,
                           expectedResponse,
                           () -> target.index(resourceType));
    }

    @ParameterizedTest
    @CsvFileSource(delimiter = '|', lineSeparator = "@\n", files = "src/test/resources/read-get.txt")
    void testReadGet(String title,
                     String jsonDml,
                     String requestUri,
                     String resourceType,
                     String resourceId,
                     int expectedStatus,
                     String expectedResponse)
            throws JSONException {

        testResourceMethod(jsonDml,
                           requestUri,
                           "GET",
                           expectedStatus,
                           expectedResponse,
                           () -> target.read(resourceType, resourceId));
    }

    @ParameterizedTest
    @CsvFileSource(delimiter = '|', lineSeparator = "@\n", files = "src/test/resources/read-relationship-get.txt")
    void testReadRelationshipGet(String title,
                                 String jsonDml,
                                 String requestUri,
                                 String resourceType,
                                 String resourceId,
                                 String relationshipName,
                                 int expectedStatus,
                                 String expectedResponse)
            throws JSONException {

        testResourceMethod(jsonDml,
                           requestUri,
                           "GET",
                           expectedStatus,
                           expectedResponse,
                           () -> target.readRelationship(resourceType, resourceId, relationshipName));
    }

    @ParameterizedTest
    @CsvFileSource(delimiter = '|', lineSeparator = "@\n", files = "src/test/resources/read-related-get.txt")
    void testReadRelatedGet(String title,
                            String jsonDml,
                            String requestUri,
                            String resourceType,
                            String resourceId,
                            String relationshipName,
                            int expectedStatus,
                            String expectedResponse)
            throws JSONException {

        testResourceMethod(jsonDml,
                           requestUri,
                           "GET",
                           expectedStatus,
                           expectedResponse,
                           () -> target.readRelated(resourceType, resourceId, relationshipName));
    }

    @ParameterizedTest
    @CsvFileSource(
        delimiter = '|',
        lineSeparator = "@\n",
        files = {
            "src/test/resources/update-patch.txt",
            "src/test/resources/update-patch-invalid.txt" })
    void testUpdatePatch(String title,
                         String jsonDml,
                         String requestUri,
                         String resourceType,
                         String resourceId,
                         String requestBody,
                         int expectedStatus,
                         String expectedResponse)
            throws JSONException {

        testResourceMethod(jsonDml,
                           requestUri,
                           "PATCH",
                           expectedStatus,
                           expectedResponse,
                           () -> target.patch(resourceType, resourceId, readObject(requestBody)));
    }

    @ParameterizedTest
    @CsvFileSource(
        delimiter = '|',
        lineSeparator = "@\n",
        files = { "src/test/resources/delete.txt" })
    void testDelete(String title,
                    String jsonDml,
                    String requestUri,
                    String resourceType,
                    String resourceId,
                    int expectedStatus,
                    String expectedResponse)
            throws JSONException {

        testResourceMethod(jsonDml,
                           requestUri,
                           "DELETE",
                           expectedStatus,
                           expectedResponse,
                           () -> target.delete(resourceType, resourceId));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://localhost:8080/test/client.js",
            "http://127.0.0.1/test/client.js" })
    void testGetClient(String requestUri) throws IOException {
        target.uriInfo = new ResteasyUriInfo(requestUri, "/");
        Response response = target.getClient();
        String clientScript = (String) response.getEntity();
        String firstLine = clientScript.lines().findFirst().orElse("");
        String expected = String.format("const baseAdminUrl = '%s';", requestUri.substring(0, requestUri.lastIndexOf('/')));
        assertEquals(expected, firstLine);
    }
}
