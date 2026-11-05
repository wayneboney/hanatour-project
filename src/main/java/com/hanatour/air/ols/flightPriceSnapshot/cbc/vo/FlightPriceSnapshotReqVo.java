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
 * 항공권 가격 스냅샷 조회 조건 (Controller -> Cbc 전달용)
 *
 * <pre>
 * dateStart / dateEnd : 수집일자(captured_at) 기준 한국시간(KST) 영업일, 양끝 포함 (YYYY-MM-DD)
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

    @Property(typeName = "조회시작일자")
    private String dateStart;

    @Property(typeName = "조회종료일자")
    private String dateEnd;

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE, false, false, true, null);
    }
}
