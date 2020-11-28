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
            'Content-Type': 'application/vnd.api+json',
            'Accept': 'application/vnd.api+json'
        },
        body: JSON.stringify(body)
    })
        .then(response => response.json())
        .catch(console.warn);
}

async function fetchData(type, path, fields = {}, filters = {}, includes = [], sort = [], pageOffset = null, pageLimit = null) {
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

    if (includes.length > 0) {
        params.push('include=' + includes.join());
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
            'Accept': 'application/vnd.api+json'
        }
    })
        .then(response => response.json())
        .then(setIncludedAttributesOnData)
        .catch(console.warn);
}

async function fetchList({ type, fields = [], filters = {}, includes = [], sort = [], pageOffset, pageLimit } = {}) {
    return fetchData(type, type, fields, filters, includes, sort, pageOffset, pageLimit);
}

async function fetchSingle({ type, id, fields = [], filters = {}, includes = [] } = {}) {
    return fetchData(type, type + '/' + id, fields, filters, includes);
}

async function update(type, id, attributes, relationships) {
    let url = baseAdminUrl + '/' + type + '/' + id;
    let body = { data: { type: type, id: id, attributes: attributes, relationships: relationships } };

    return fetch(url, {
        method: 'PATCH',
        headers: {
            'Content-Type': 'application/vnd.api+json',
            'Accept': 'application/vnd.api+json'
        },
        body: JSON.stringify(body)
    })
        .then(response => response.json())
        .catch(console.warn);
}

async function remove(type, id) {
    let url = baseAdminUrl + '/' + type + '/' + id;

    return fetch(url, {
        method: 'DELETE',
        headers: {
            'Accept': 'application/vnd.api+json'
        }
    })
        .then(response => response.json())
        .catch(console.warn);
}

export { create, fetchList, fetchSingle, update, remove };
