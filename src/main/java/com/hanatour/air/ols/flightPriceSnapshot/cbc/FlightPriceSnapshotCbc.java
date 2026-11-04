package com.hanatour.air.ols.flightPriceSnapshot.cbc;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;

public interface FlightPriceSnapshotCbc {

    /** 스냅샷 목록 조회 → {"totalCount":n,"list":[...]} */
    public String getSnapshotList(FlightPriceSnapshotReqVo vo);

}
