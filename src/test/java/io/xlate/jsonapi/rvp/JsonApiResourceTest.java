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
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.skyscreamer.jsonassert.JSONAssert;

import io.xlate.jsonapi.rvp.internal.DefaultJsonApiHandler;
import io.xlate.jsonapi.rvp.internal.validation.boundary.TransactionalValidator;
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

    @Test
    void testIndexKnownTypeReturns200() throws JSONException {
        Set<JsonApiResourceType<?>> resourceTypes = new HashSet<>();
        resourceTypes.add(JsonApiResourceType.define("posts", Post.class).build());
        target.initialize(resourceTypes);
        Mockito.when(target.request.getMethod()).thenReturn("GET");
        Mockito.when(target.handlers.iterator())
               .thenReturn(handlerIterator());
        target.uriInfo = new ResteasyUriInfo(URI.create("/test/posts?fields[posts]=title&filter[id]=-1"));

        Response response = target.index("posts");
        assertNotNull(response);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        JSONAssert.assertEquals("{'jsonapi':{'version':'1.0'},'data':[]}",
                                String.valueOf(response.getEntity()),
                                true);
    }

    @Test
    void testIndexUnknownTypeReturns404() throws JSONException {
        Set<JsonApiResourceType<?>> resourceTypes = new HashSet<>();
        resourceTypes.add(JsonApiResourceType.define("posts", Post.class).build());
        target.initialize(resourceTypes);
        Mockito.when(target.request.getMethod()).thenReturn("GET");
        Mockito.when(target.handlers.iterator())
               .thenReturn(handlerIterator());
        target.uriInfo = new ResteasyUriInfo(URI.create("/test/comments?fields[comments]=text&filter[id]=-1"));

        Response response = target.index("comments");
        assertNotNull(response);
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        JSONAssert.assertEquals("{'errors':[{'status':'404','title':'Not Found','detail':'The requested resource can not be found.'}]}",
                                String.valueOf(response.getEntity()),
                                true);
    }

}
