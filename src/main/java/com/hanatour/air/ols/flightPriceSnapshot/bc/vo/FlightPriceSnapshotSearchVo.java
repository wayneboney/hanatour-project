package com.hanatour.air.ols.flightPriceSnapshot.bc.vo;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import hntframe.run.common.io.type.annotation.CompositeType;
import hntframe.run.common.io.type.annotation.Property;
import hntframe.run.oltp.ext.annotation.LocalName;
import hntframe.run.oltp.ext.vo.AbstractVo;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * 항공권가격스냅샷조회조건
 *
 * <pre>
 * 목록 조회: reservationCode(필수) + startDt/endDt(YYYYMMDD, 선택) + maxRows
 * 경쟁운임 조회: reservationCode(필수) + snapshotId(필수)
 * </pre>
 */
@CompositeType
@LocalName("항공권가격스냅샷조회조건")
@XmlAccessorType(XmlAccessType.FIELD)
@XStreamAlias("FlightPriceSnapshotSearchVo")
@XmlRootElement(name = "FlightPriceSnapshotSearchVo")
public class FlightPriceSnapshotSearchVo extends AbstractVo {

    private static final long serialVersionUID = 1L;

    @Property(typeName = "예약코드", length = 256)
    private String reservationCode;

    @Property(typeName = "스냅샷ID", length = 256)
    private String snapshotId;

    @Property(typeName = "수집시작일자", length = 256)
    private String startDt;

    @Property(typeName = "수집종료일자", length = 256)
    private String endDt;

    @Property(typeName = "최대조회건수", length = 256)
    private int maxRows;

    public String getReservationCode() {
        return reservationCode;
    }

    public void setReservationCode(String reservationCode) {
        this.reservationCode = reservationCode;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getStartDt() {
        return startDt;
    }

    public void setStartDt(String startDt) {
        this.startDt = startDt;
    }

    public String getEndDt() {
        return endDt;
    }

    public void setEndDt(String endDt) {
        this.endDt = endDt;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE, false, false, true, null);
    }
}
