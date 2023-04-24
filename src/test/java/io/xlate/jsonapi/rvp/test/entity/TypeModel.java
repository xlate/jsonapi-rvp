package io.xlate.jsonapi.rvp.test.entity;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "TYPE_MODELS")
public class TypeModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "primitive_int", nullable = false, columnDefinition = "INT DEFAULT 0")
    private int primitiveInt;

    @Column(name = "wrapped_int")
    private Integer wrappedInt;

    @Column(name = "util_date")
    private Date utilDate;

    @Column(name = "string")
    private String string;

    @Column(name = "primitive_boolean", nullable = false, columnDefinition = "INT DEFAULT 0")
    private boolean primitiveBoolean;

    @Column(name = "wrapped_boolean")
    private Boolean wrappedBoolean;

    @Column(name = "offset_datetime")
    private OffsetDateTime offsetDateTime;

    @Column(name = "offset_time")
    private OffsetTime offsetTime;

    @Column(name = "zoned_datetime")
    private ZonedDateTime zonedDateTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getPrimitiveInt() {
        return primitiveInt;
    }

    public void setPrimitiveInt(int primitiveInt) {
        this.primitiveInt = primitiveInt;
    }

    public Integer getWrappedInt() {
        return wrappedInt;
    }

    public void setWrappedInt(Integer wrappedInt) {
        this.wrappedInt = wrappedInt;
    }

    public Date getUtilDate() {
        return utilDate;
    }

    public void setUtilDate(Date utilDate) {
        this.utilDate = utilDate;
    }

    public String getString() {
        return string;
    }

    public void setString(String string) {
        this.string = string;
    }

    public boolean isPrimitiveBoolean() {
        return primitiveBoolean;
    }

    public void setPrimitiveBoolean(boolean primitiveBoolean) {
        this.primitiveBoolean = primitiveBoolean;
    }

    public Boolean getWrappedBoolean() {
        return wrappedBoolean;
    }

    public void setWrappedBoolean(Boolean wrappedBoolean) {
        this.wrappedBoolean = wrappedBoolean;
    }

    public OffsetDateTime getOffsetDateTime() {
        return offsetDateTime;
    }

    public void setOffsetDateTime(OffsetDateTime offsetDateTime) {
        this.offsetDateTime = offsetDateTime;
    }

    public OffsetTime getOffsetTime() {
        return offsetTime;
    }

    public void setOffsetTime(OffsetTime offsetTime) {
        this.offsetTime = offsetTime;
    }

    public ZonedDateTime getZonedDateTime() {
        return zonedDateTime;
    }

    public void setZonedDateTime(ZonedDateTime zonedDateTime) {
        this.zonedDateTime = zonedDateTime;
    }

}
