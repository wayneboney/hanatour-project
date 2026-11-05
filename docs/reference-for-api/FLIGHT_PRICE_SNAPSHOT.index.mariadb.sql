-- FLIGHT_PRICE_SNAPSHOT 기간 조회용 인덱스 (MariaDB, price 데이터소스 AIR01)
-- 프론트 대시보드는 예약코드 없이 captured_at 범위로만 조회한다.
-- 기존 idx_snapshot_reservation_time (reservation_code, captured_at) 는 선두 컬럼이 reservation_code 라
-- 기간 조회에 사용할 수 없어 풀스캔이 발생한다. 운영 반영 전 DBA 검토 필요 (대용량 테이블이면 온라인 DDL 시간 확인)
CREATE INDEX idx_snapshot_captured_at ON FLIGHT_PRICE_SNAPSHOT (captured_at);
