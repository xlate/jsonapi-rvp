package io.xlate.jsonapi.rs.boundary;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

@Provider
public class CORSFilter implements ContainerResponseFilter {

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";
    //private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String MAX_AGE = "Access-Control-Max-Age";

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {

        MultivaluedMap<String, Object> headers = responseContext.getHeaders();

        if (requestContext.getHeaders().containsKey("Origin")) {
            headers.add(ALLOW_ORIGIN, requestContext.getHeaders().getFirst("Origin"));
        } else {
            headers.add(ALLOW_ORIGIN, "*");
        }

        headers.add(ALLOW_CREDENTIALS, "true");

        if ("OPTIONS".equals(requestContext.getMethod())) {
            headers.add(ALLOW_METHODS, "GET,PATCH,POST,PUT,DELETE,OPTIONS,HEAD");
            headers.add(ALLOW_HEADERS, "Origin,Content-Type,Accept,Authorization");
            headers.add(MAX_AGE, "1209600");
        }
    }
}
