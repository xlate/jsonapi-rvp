package io.xlate.jsonapi.rvp.test.entity;

import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.identity.H2IdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;

public class H2DialectPatch extends H2Dialect {

    public static class H2IdentityColumnSupportPatch extends H2IdentityColumnSupport {
        @Override
        public String getIdentityInsertString() {
            return null;
        }
    }

    @Override
    public IdentityColumnSupport getIdentityColumnSupport() {
        return new H2IdentityColumnSupportPatch();
    }

}
