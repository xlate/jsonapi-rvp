/*
 * Copyright 2021 xlate.io LLC, http://www.xlate.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * jsonapi-rvp is an implementation of a
 * <a href="https://jsonapi.org/">JSON:API</a> server in Java using JAX-RS, Bean
 * Validation, and Java Persistence (JPA).
 *
 * @author Michael Edgar
 * @see <a href="https://github.com/xlate/jsonapi-rvp" target=
 *      "_blank">jsonapi-rvp on GitHub</a>
 */
module io.xlate.jsonapi.rvp {

    requires jakarta.enterprise.cdi.api;
    requires java.desktop;
    requires java.inject;
    requires java.logging;
    requires java.persistence;
    requires java.transaction;
    requires java.validation;

    requires transitive java.json;
    requires transitive java.ws.rs;

    exports io.xlate.jsonapi.rvp;

    opens io.xlate.jsonapi.rvp.internal.validation.boundary;

}
