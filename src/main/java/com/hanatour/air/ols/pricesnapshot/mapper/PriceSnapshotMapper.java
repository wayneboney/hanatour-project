package com.hanatour.air.ols.pricesnapshot.mapper;

import com.hanatour.air.ols.pricesnapshot.vo.PriceSnapshotSearchVo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * FLIGHT_PRICE_SNAPSHOT Mapper
 * - fare1~fare20 반복 컬럼은 Map 으로 받고 Service 에서 리스트로 변환한다. (Map 키 = SQL alias, camelCase)
 * - 프로젝트에서 @MapperScan 을 쓰고 있다면 @Mapper 는 제거해도 된다.
 */
@Mapper
public interface PriceSnapshotMapper {
	List<Map<String, Object>> selectSnapshotList(PriceSnapshotSearchVo search);
}
