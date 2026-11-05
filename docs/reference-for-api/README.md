# 항공API 개발자 참고 자료

ols 는 price(MariaDB) 데이터소스 설정이 없어서, **DB 조회는 항공API 에서 수행**하고 ols 는 호출만 한다.
처음에 ols 에서 직접 DB 를 조회하는 방식으로 작성했던 코드를 참고용으로 보관한 것. **ols 에서는 사용하지 않음.**

| 파일 | 내용 |
|---|---|
| `FlightPriceSnapshotMapper.xml` | MariaDB SQL (기간 조회, 스냅샷 81개 컬럼, 시각 `+09:00` ISO, 금액 문자열). 미실행 검증. **예약·탑승객 14개 필드의 조인은 포함하지 않음** |
| `FlightPriceSnapshotVo.java.txt` | 응답 행 VO (81개 필드 + 예약·탑승객 선택 필드 14개). Jackson 이 getter 가 아닌 **필드 기준**으로 직렬화하도록 `@JsonAutoDetect` 사용 (`qCharge` 가 `qcharge` 로 깨지는 문제 방지) |
| `FLIGHT_PRICE_SNAPSHOT.index.mariadb.sql` | 기간 조회용 인덱스 `captured_at` (없으면 풀스캔) |

응답 규격은 프론트팀 `API_CONTRACT.md` 기준 (`docs/flightPriceSnapshot.md` 3번 참고).

**참고**: 최신 프론트 HTML 은 위 81개 필드 외에 예약·탑승객 필드(`res_id`, `res_cd`, `res_adt_cnt`, `passenger_gender_summary` 등)를 추가로 읽는다. 형식 규칙(성별/연령 요약 문자열 포맷 등)과 주의사항은 `docs/flightPriceSnapshot.md` 4-1 참고.
