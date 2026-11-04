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
 * 항공권가격스냅샷
 */
@CompositeType
@LocalName("항공권가격스냅샷")
@XmlAccessorType(XmlAccessType.FIELD)
@XStreamAlias("FlightPriceSnapshotVo")
@XmlRootElement(name = "FlightPriceSnapshotVo")
@Getter
@Setter
public class FlightPriceSnapshotVo extends AbstractVo {

    private static final long serialVersionUID = 1L;

    @Property(typeName = "전체건수", length = 256)
    private int totalCount;

    @Property(typeName = "스냅샷ID", length = 256)
    private String snapshotId;

    @Property(typeName = "예약코드", length = 256)
    private String reservationCode;

    @Property(typeName = "여정구분", length = 256)
    private String tripType;

    @Property(typeName = "직항여부", length = 256)
    private String directOnlyYn;

    @Property(typeName = "출발일", length = 256)
    private String depDate;

    @Property(typeName = "출발공항코드", length = 256)
    private String depAirport;

    @Property(typeName = "출발편항공사코드", length = 256)
    private String depAirlineCode;

    @Property(typeName = "출발편편명", length = 256)
    private String depFlightNo;

    @Property(typeName = "귀국일", length = 256)
    private String returnDate;

    @Property(typeName = "귀국공항코드", length = 256)
    private String returnAirport;

    @Property(typeName = "귀국편항공사코드", length = 256)
    private String returnAirlineCode;

    @Property(typeName = "귀국편편명", length = 256)
    private String returnFlightNo;

    @Property(typeName = "프로모션카드코드", length = 256)
    private String promoCardCode;

    @Property(typeName = "좌석등급", length = 256)
    private String seatClass;

    @Property(typeName = "탑승객유형", length = 256)
    private String passengerType;

    @Property(typeName = "기본운임", length = 256)
    private String baseFare;

    @Property(typeName = "제세공과금", length = 256)
    private String tax;

    @Property(typeName = "유류할증료", length = 256)
    private String fuelSurcharge;

    @Property(typeName = "당사현재순위", length = 256)
    private String currentRank;

    @Property(typeName = "가격수집시각", length = 256)
    private String capturedAt;

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE, false, false, true, null);
    }
}
