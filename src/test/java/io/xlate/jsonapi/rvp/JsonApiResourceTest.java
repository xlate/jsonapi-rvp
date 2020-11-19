package io.xlate.jsonapi.rvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.enterprise.inject.Instance;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.validation.Validation;
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
    }

    @AfterEach
    void tearDown() {
        em.close();
    }

    @ParameterizedTest
    @CsvFileSource(delimiter = '|', lineSeparator = "@\n", files = "src/test/resources/index-get.txt")
    void testIndexGet(String title,
                      String requestUri,
                      String resourceType,
                      int expectedStatus,
                      String expectedResponse) throws JSONException {

        Set<JsonApiResourceType<?>> resourceTypes = new HashSet<>();
        resourceTypes.add(JsonApiResourceType.define("posts", Post.class).build());
        resourceTypes.add(JsonApiResourceType.define("comments", Comment.class).build());
        target.initialize(resourceTypes);

        Mockito.when(target.request.getMethod()).thenReturn("GET");
        Mockito.when(target.handlers.iterator()).thenReturn(handlerIterator());
        target.uriInfo = new ResteasyUriInfo(URI.create(requestUri));

        Response response = target.index(resourceType);
        assertNotNull(response);

        String responseEntity = String.valueOf(response.getEntity());

        try {
            assertEquals(expectedStatus, response.getStatus());
            JSONAssert.assertEquals(expectedResponse, responseEntity, true);
        } catch (Throwable t) {
            System.out.println(responseEntity);
            throw t;
        }
    }

}
