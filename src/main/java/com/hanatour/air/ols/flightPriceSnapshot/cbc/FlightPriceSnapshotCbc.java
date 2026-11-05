package com.hanatour.air.ols.flightPriceSnapshot.cbc;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;

public interface FlightPriceSnapshotCbc {

    /** 스냅샷 조회 → {"business_date":..,"as_of":..,"is_complete":true,"total":n,"rows":[...]} */
    public String getSnapshots(FlightPriceSnapshotReqVo vo);

}
