package com.hanatour.air.ols.flightPriceSnapshot.cbc.vo;

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
 * 항공권가격스냅샷경쟁운임
 *
 * <pre>
 * 테이블의 FAREn / FAREn_AGENCY_CODE / FAREn_AGENCY_NAME (n=1~20) 반복 컬럼을 행으로 풀어낸 결과
 * </pre>
 */
@CompositeType
@LocalName("항공권가격스냅샷경쟁운임")
@XmlAccessorType(XmlAccessType.FIELD)
@XStreamAlias("FlightPriceSnapshotCompetitorVo")
@XmlRootElement(name = "FlightPriceSnapshotCompetitorVo")
public class FlightPriceSnapshotCompetitorVo extends AbstractVo {

    private static final long serialVersionUID = 1L;

    @Property(typeName = "스냅샷ID", length = 256)
    private String snapshotId;

    @Property(typeName = "경쟁운임순번", length = 256)
    private int rankNo;

    @Property(typeName = "경쟁운임", length = 256)
    private String fare;

    @Property(typeName = "여행사코드", length = 256)
    private String agencyCode;

    @Property(typeName = "경쟁사명", length = 256)
    private String agencyName;

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public int getRankNo() {
        return rankNo;
    }

    public void setRankNo(int rankNo) {
        this.rankNo = rankNo;
    }

    public String getFare() {
        return fare;
    }

    public void setFare(String fare) {
        this.fare = fare;
    }

    public String getAgencyCode() {
        return agencyCode;
    }

    public void setAgencyCode(String agencyCode) {
        this.agencyCode = agencyCode;
    }

    public String getAgencyName() {
        return agencyName;
    }

    public void setAgencyName(String agencyName) {
        this.agencyName = agencyName;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE, false, false, true, null);
    }
}
