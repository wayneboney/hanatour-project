package com.hanatour.air.ols.flightPriceSnapshot.cbc.vo;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import hntframe.run.common.io.type.annotation.CompositeType;
import hntframe.run.common.io.type.annotation.Property;
import hntframe.run.oltp.ext.annotation.LocalName;
import hntframe.run.oltp.ext.vo.AbstractVo;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * 항공권 가격 스냅샷 조회 요청 VO (UI -> Controller 요청 본문)
 *
 * <pre>
 * reservationCode(필수), startDt/endDt(YYYYMMDD, 선택), maxRows(선택, 기본 50 / 최대 200)
 * </pre>
 */
@CompositeType
@LocalName("FlightPriceSnapshotReqVo")
@XmlAccessorType(XmlAccessType.FIELD)
@XStreamAlias("FlightPriceSnapshotReqVo")
@XmlRootElement(name = "FlightPriceSnapshotReqVo")
@Getter
@Setter
public class FlightPriceSnapshotReqVo extends AbstractVo {

    private static final long serialVersionUID = 1L;

    @Property(typeName = "예약코드")
    private String reservationCode;

    @Property(typeName = "수집시작일자")
    private String startDt;

    @Property(typeName = "수집종료일자")
    private String endDt;

    @Property(typeName = "최대조회건수")
    private Integer maxRows;

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE, false, false, true, null);
    }
}
