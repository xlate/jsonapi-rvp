package io.xlate.jsonapi.rs;

import java.net.URI;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.json.JsonObject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

public abstract class AbstractResource<E extends JsonApiEntity> {

	protected final transient Class<E> entityClass;

	@Context
	protected Request rsRequest;

	@Context
	protected UriInfo uriInfo;

    @Context
    protected SecurityContext security;

	@PersistenceContext
	protected EntityManager persistenceContext;

	@Inject
	protected Validator validator;

	CacheControl cacheControl = new CacheControl();

	public AbstractResource(final Class<E> entityClass) {
		this.entityClass = entityClass;
		cacheControl.setPrivate(true);
	}

	public abstract Response create(JsonObject input);
	public abstract Response read(long id);
	public abstract Response patch(long id, JsonObject input);
    public abstract Response update(long id, JsonObject input);
	public abstract Response delete(long id);
	protected abstract AbstractJsonSerializer<E> getSerializer();

	protected void validate(E entity) {
	    Set<ConstraintViolation<E>> violations = validator.validate(entity);

	    if (!violations.isEmpty()) {
	        throw new ConstraintViolationException(violations);
	    }
	}

	/*protected Account getUserAccount() {
	    return ((AccountCallerPrincipal) security.getUserPrincipal()).getAccount();
	}

	protected String getUserFullName() {
	    return getUserAccount().getName();
	}*/

	protected URI getUri(String method, long id) {
		UriBuilder builder = UriBuilder.fromUri(uriInfo.getBaseUri());
		builder.path(getClass());
		builder.path(getClass(), method);
		return builder.build(id);
	}

	protected static Response notFound() {
		return Response.status(Status.NOT_FOUND).build(); //EntityNotFoundMapper.notFound();
	}

	protected static Response ok() {
		ResponseBuilder ok = Response.ok("{\"reason\": \"Success\"}");
		ok.type(MediaType.APPLICATION_JSON_TYPE);
		return ok.build();
	}

	protected Response ok(Object entity) {
		EntityTag etag = new EntityTag(Integer.toString(entity.hashCode()));
		ResponseBuilder builder;
		builder = rsRequest.evaluatePreconditions(etag);

		if (builder == null) {
			builder = Response.ok(entity);
			builder.tag(etag);
		}

		builder.cacheControl(cacheControl);

		return builder.build();
	}

	private static EntityTag entityTag(Date updatedAt, long count) {
		StringBuilder buffer = new StringBuilder();

		if (updatedAt != null) {
			buffer.append(Long.toString(updatedAt.getTime()));
		} else {
			buffer.append('0');
		}

		buffer.append('.');

		buffer.append(Long.toString(count));

		return new EntityTag(Long.toHexString(buffer.toString().hashCode()));
	}

	@Deprecated
	protected Response ok(GenericEntity<List<E>> entityList) {
		Date maxUpdatedAt = null;

		for (E entity : entityList.getEntity()) {
			Date updatedAt = entity.getUpdatedAt();

			if (updatedAt == null) {
			    continue;
			}

			if (maxUpdatedAt == null || maxUpdatedAt.before(updatedAt)) {
				maxUpdatedAt = updatedAt;
			}
		}

		ResponseBuilder builder;
		builder = Response.ok(entityList);
		builder.tag(entityTag(maxUpdatedAt, entityList.getEntity().size()));

		builder.cacheControl(cacheControl);
		return builder.build();
	}

	protected ResponseBuilder created(long id) {
		return Response.created(getUri("read", id));
	}

	protected Response seeOther(long id) {
		return Response.seeOther(getUri("read", id)).build();
	}

	protected Response createImpl(E entity) {
	    validate(entity);
		persist(entity);

		return created(entity.getId()).entity(entity).build();
	}

	private void persist(final E entity) {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		entity.setCreated("UNKNOWN", now);
		persistenceContext.persist(entity);
	}

	protected List<E> findAll() {
		CriteriaBuilder builder = persistenceContext.getCriteriaBuilder();

		CriteriaQuery<E> query = builder.createQuery(entityClass);
		Root<E> entity = query.from(entityClass);
		query.select(entity);

		return persistenceContext.createQuery(query).getResultList();
	}

	protected ResponseBuilder evaluate(String queryName, String eTagHeader, Object... params) {
		if (eTagHeader == null) {
			return null;
		}

		Query query = persistenceContext.createNamedQuery(queryName);

		if (params != null) {
			for (int i = 0; i < params.length; i++) {
				query.setParameter(i + 1, params[i]);
			}
		}

		Object[] result;

		try {
			result = (Object[]) query.getSingleResult();
		} catch (@SuppressWarnings("unused") NoResultException e) {
			return null;
		}

		EntityTag eTag = entityTag((Date) result[0], (Long) result[1]);
		return rsRequest.evaluatePreconditions(eTag);
	}

	protected <T> List<T> findByQuery(Class<T> resultClass, String queryName, Object... params) {
	    return findByQuery(resultClass, queryName, 0, 100, params);
	}

	protected <T> List<T> findByQuery(Class<T> resultClass, String queryName, int startPosition, int maxResults, Object... params) {
		TypedQuery<T> query = persistenceContext.createNamedQuery(queryName, resultClass);

		int p = 0;

		for (Object param : params) {
			query.setParameter(++p, param);
		}

		return query.setFirstResult(startPosition)
		            .setMaxResults(maxResults)
		            .getResultList();
	}

	protected E find(final Long id) {
	    E entity = persistenceContext.find(entityClass, id);
	    if (entity != null) {
	        return entity;
	    }
		throw new NotFoundException();
	}

	protected Response retrieveImpl(final Long id) {
		return responseFor(find(id));
	}

	protected Response responseFor(E entity) {
		if (entity != null) {
			return ok(getSerializer().serialize(entity, uriInfo));
		}
		return notFound();
	}

	protected Response updateImpl(long id, final E entity) {
	    validate(entity);
		E dbEntity = find(id);

		if (dbEntity != null) {
			entity.setId(id);
			entity.setUpdated("UNKNOWN", new Timestamp(System.currentTimeMillis()));
			return ok(getSerializer().serialize(persistenceContext.merge(entity), uriInfo));
		}
		return notFound();
	}

	protected void remove(final E entity) {
		persistenceContext.remove(entity);
	}

	protected Response deleteImpl(final Long id) {
		E entity = find(id);

		if (entity != null) {
			persistenceContext.remove(entity);
			return Response.noContent().build();
		}

		return notFound();
	}
}