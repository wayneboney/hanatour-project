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
 * 항공권가격스냅샷
 */
@CompositeType
@LocalName("항공권가격스냅샷")
@XmlAccessorType(XmlAccessType.FIELD)
@XStreamAlias("FlightPriceSnapshotVo")
@XmlRootElement(name = "FlightPriceSnapshotVo")
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

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getReservationCode() {
        return reservationCode;
    }

    public void setReservationCode(String reservationCode) {
        this.reservationCode = reservationCode;
    }

    public String getTripType() {
        return tripType;
    }

    public void setTripType(String tripType) {
        this.tripType = tripType;
    }

    public String getDirectOnlyYn() {
        return directOnlyYn;
    }

    public void setDirectOnlyYn(String directOnlyYn) {
        this.directOnlyYn = directOnlyYn;
    }

    public String getDepDate() {
        return depDate;
    }

    public void setDepDate(String depDate) {
        this.depDate = depDate;
    }

    public String getDepAirport() {
        return depAirport;
    }

    public void setDepAirport(String depAirport) {
        this.depAirport = depAirport;
    }

    public String getDepAirlineCode() {
        return depAirlineCode;
    }

    public void setDepAirlineCode(String depAirlineCode) {
        this.depAirlineCode = depAirlineCode;
    }

    public String getDepFlightNo() {
        return depFlightNo;
    }

    public void setDepFlightNo(String depFlightNo) {
        this.depFlightNo = depFlightNo;
    }

    public String getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(String returnDate) {
        this.returnDate = returnDate;
    }

    public String getReturnAirport() {
        return returnAirport;
    }

    public void setReturnAirport(String returnAirport) {
        this.returnAirport = returnAirport;
    }

    public String getReturnAirlineCode() {
        return returnAirlineCode;
    }

    public void setReturnAirlineCode(String returnAirlineCode) {
        this.returnAirlineCode = returnAirlineCode;
    }

    public String getReturnFlightNo() {
        return returnFlightNo;
    }

    public void setReturnFlightNo(String returnFlightNo) {
        this.returnFlightNo = returnFlightNo;
    }

    public String getPromoCardCode() {
        return promoCardCode;
    }

    public void setPromoCardCode(String promoCardCode) {
        this.promoCardCode = promoCardCode;
    }

    public String getSeatClass() {
        return seatClass;
    }

    public void setSeatClass(String seatClass) {
        this.seatClass = seatClass;
    }

    public String getPassengerType() {
        return passengerType;
    }

    public void setPassengerType(String passengerType) {
        this.passengerType = passengerType;
    }

    public String getBaseFare() {
        return baseFare;
    }

    public void setBaseFare(String baseFare) {
        this.baseFare = baseFare;
    }

    public String getTax() {
        return tax;
    }

    public void setTax(String tax) {
        this.tax = tax;
    }

    public String getFuelSurcharge() {
        return fuelSurcharge;
    }

    public void setFuelSurcharge(String fuelSurcharge) {
        this.fuelSurcharge = fuelSurcharge;
    }

    public String getCurrentRank() {
        return currentRank;
    }

    public void setCurrentRank(String currentRank) {
        this.currentRank = currentRank;
    }

    public String getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(String capturedAt) {
        this.capturedAt = capturedAt;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE, false, false, true, null);
    }
}
