const JSON_API_MEDIA_TYPE = 'application/vnd.api+json';

function getIncluded(related, included = []) {
    if (Array.isArray(related)) {
        if (related.length > 0) {
            return included.filter(i => {
                return related.filter(id => i.type == id.type
                    && i.id == id.id).length > 0
            });
        }
    } else if (related) {
        // Single - fetch index zero
        return included.filter(i => i.type == related.type
            && i.id == related.id)[0];
    }

    return null;
}

function setRelatedAttributes(resource, included) {
    if (resource.relationships && included) {
        const keys = Object.keys(resource.relationships);

        keys.forEach(key => {
            const relationship = resource.relationships[key];

            if (Array.isArray(relationship.data)) {
                relationship.data.forEach(related => {
                    const include = getIncluded(related, included);
                    related.attributes = include.attributes;
                });
            } else if (relationship.data) {
                const related = relationship.data;
                const include = getIncluded(related, included);
                related.attributes = include.attributes;
            }
        });
    }
}

function setIncludedAttributesOnData(response) {
    if (Array.isArray(response.data)) {
        response.data.forEach(resrc => {
            setRelatedAttributes(resrc, response.included);
        });
    } else {
        setRelatedAttributes(response.data, response.included);
    }

    return response;
}

async function create(type, attributes, relationships) {
    let url = baseAdminUrl + '/' + type;
    let body = { data: { type: type, attributes: attributes, relationships: relationships } };

    return fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': JSON_API_MEDIA_TYPE,
            'Accept': JSON_API_MEDIA_TYPE
        },
        body: JSON.stringify(body)
    })
        .then(response => response.json())
        .catch(console.warn);
}

async function fetchData(path, fields = {}, filters = {}, include = [], sort = [], pageOffset = null, pageLimit = null) {
    let url = baseAdminUrl + '/' + path;
    let params = [];

    let fieldParams = Object.keys(fields).map(key => 'fields[' + key + ']=' + fields[key].join());

    if (fieldParams.length > 0) {
        fieldParams.forEach(p => params.push(p));
    }

    let filterParams = Object.keys(filters).map(key => 'filter[' + encodeURIComponent(key) + ']=' + encodeURIComponent(filters[key]));

    if (filterParams.length > 0) {
        filterParams.forEach(p => params.push(p));
    }

    if (include.length > 0) {
        params.push('include=' + include.join());
    }

    if (sort.length > 0) {
        params.push('sort=' + sort.join());
    }

    if (pageOffset) {
        params.push('page[offset]=' + pageOffset);
    }

    if (pageLimit) {
        params.push('page[limit]=' + pageLimit);
    }

    if (params.length > 0) {
        url += '?' + params.join('&');
    }

    return fetch(url, {
        method: 'GET',
        headers: {
            'Accept': JSON_API_MEDIA_TYPE
        }
    })
        .then(response => response.json())
        .then(setIncludedAttributesOnData)
        .catch(console.warn);
}

/**
 * @param {String} type resource type (required)
 * @param {Object} params request parameters
 * @param {Object.<string, Array.<string>>} [params.fields = {}] fields to be included
 * @param {Object.<string, Array.<string>>} [params.filters = {}] selection criteria
 * @param {Array.<string>} [params.include = []] included relationships
 * @param {Array.<string>} [params.sort = []] result sort criteria
 * @param {number} [params.pageOffset] position of the first result, numbered from 0
 * @param {number} [params.pageLimit] position of the last result (exclusive), numbered from 0
 */
async function fetchList(type, { fields = [], filters = {}, include = [], sort = [], pageOffset, pageLimit } = {}) {
    return fetchData(type, fields, filters, include, sort, pageOffset, pageLimit);
}

/**
 * @param {String} type resource type (required)
 * @param {String} id resource identifier (required)
 * @param {Object} params request parameters
 * @param {Object.<string, Array.<string>>} [params.fields = {}] fields to be included
 * @param {Object.<string, Array.<string>>} [params.filters = {}] selection criteria
 * @param {Array.<string>} [params.include = []] included relationships
 */
async function fetchSingle(type, id, { fields = {}, filters = {}, include = [] } = {}) {
    return fetchData(type + '/' + id, fields, filters, include);
}

/**
 * @param {String} type resource type (required)
 * @param {String} id resource identifier (required)
 * @param {Object.<string, any>} attributes map of attributes and the values to update
 * @param {Object.<string, any>} relationships map of relationships to be updated
 */
async function update(type, id, attributes, relationships) {
    let url = baseAdminUrl + '/' + type + '/' + id;
    let body = { data: { type: type, id: id, attributes: attributes, relationships: relationships } };

    return fetch(url, {
        method: 'PATCH',
        headers: {
            'Content-Type': JSON_API_MEDIA_TYPE,
            'Accept': JSON_API_MEDIA_TYPE
        },
        body: JSON.stringify(body)
    })
        .then(response => response.json())
        .catch(console.warn);
}

/**
 * Delete the resource identified by type and identifier.
 *
 * @param {String} type resource type (required)
 * @param {String} id resource identifier (required)
 */
async function remove(type, id) {
    let url = baseAdminUrl + '/' + type + '/' + id;

    return fetch(url, {
        method: 'DELETE',
        headers: {
            'Accept': JSON_API_MEDIA_TYPE
        }
    })
        .then(response => response.json())
        .catch(console.warn);
}

export { create, fetchList, fetchSingle, update, remove };
