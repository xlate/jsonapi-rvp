package io.xlate.jsonapi.rs;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;

@MappedSuperclass
public abstract class JsonApiEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", updatable = false)
    protected long id;

    @Column(name = "CREATED_BY", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "UPDATED_BY", nullable = false)
    private String updatedBy;

    @Column(name = "UPDATED_AT", nullable = false)
    private Timestamp updatedAt;

    @Transient
    protected Map<String, Long> relationshipCounts = Collections.emptyMap();

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (id ^ (id >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof JsonApiEntity)) {
            return false;
        }
        JsonApiEntity other = (JsonApiEntity) obj;
        if (id != other.id) {
            return false;
        }
        return true;
    }

    public void setCreated(String createdBy, Timestamp createdAt) {
        setCreatedBy(createdBy);
        setCreatedAt(createdAt);
        setUpdated(createdBy, createdAt);
    }

    public void setUpdated(String updatedBy, Timestamp updatedAt) {
        setUpdatedBy(updatedBy);
        setUpdatedAt(updatedAt);
    }

    protected void copyAuditDetails(Collection<? extends JsonApiEntity> associations) {
        for (JsonApiEntity entity : associations) {
            entity.setCreatedBy(this.getCreatedBy());
            entity.setCreatedAt(this.getCreatedAt());
            entity.setUpdatedBy(this.getUpdatedBy());
            entity.setUpdatedAt(this.getUpdatedAt());
        }
    }

    public long getId() {
        return this.id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    private void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    private void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    private void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    private void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, Long> getRelationshipCounts() {
        return relationshipCounts;
    }

    public void setRelationshipCounts(Map<String, Long> relationshipCounts) {
        this.relationshipCounts = relationshipCounts;
    }
}
