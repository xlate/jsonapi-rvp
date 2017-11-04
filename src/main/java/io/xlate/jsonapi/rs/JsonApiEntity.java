package io.xlate.jsonapi.rs;

import java.sql.Timestamp;
import java.util.Collection;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;

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
}
