package com.hanatour.air.ols.flightPriceSnapshot;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.PrometheusUpstreamException;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.common.cmn.CoreUtil;
import com.hanatour.common.cmn.certification.vo.MemberInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 항공권 가격 대시보드 Controller (스냅샷 조회 + Prometheus 중계)
 * UI(flightPricingDashboard.html) -> portal /air/** -> ols
 *
 * <pre>
 * 1) 스냅샷 조회: ols -> 항공API (DB 조회는 항공API 에서 수행)
 *    GET /air/flightPrice/snapshots?date_start=2026-11-04&date_end=2026-11-04&timezone=Asia%2FSeoul
 *
 * 2) Prometheus 읽기 전용 중계: ols -> Prometheus (UI 에서는 내부망 Prometheus 에 직접 접근 불가)
 *    HTML 설정 prometheus.proxyPath = '/air/monitoring/prometheus' 를 그대로 사용하기 위해 경로를 메서드마다 전체로 지정
 *    GET /air/monitoring/prometheus/api/v1/query        ?query=&time=
 *    GET /air/monitoring/prometheus/api/v1/query_range  ?query=&start=&end=&step=
 *    GET /air/monitoring/prometheus/api/v1/rules        ?type=alert   (알림 규칙 조회)
 *    (HTML 의 호출부 `proxyPath + '/api/v1/' + path` 확인 완료: path = query, query_range, rules 3종)
 *    그 외 경로(/api/v1/admin, /-/reload 등)는 매핑하지 않으므로 전달되지 않는다.
 * </pre>
 *
 * [접근 제어] 모든 엔드포인트는 로그인한 사용자만 허용한다.
 *   - 로그인 정보 없음(세션 없음/아이디 없음) → 401 (HTML 이 로그인 경고창을 띄움)
 *   - ENFORCE_ALLOWLIST=true 이면 허용 목록(ALLOWED_USER_IDS)에 없는 사용자는 403 (HTML 이 접근권한 오류 문구를 표시)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlightPriceSnapshotController {

    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    /** 포털이 로그인 사용자 정보를 담아두는 서버 세션 속성명 (운영 로그로 확인함: 값은 MemberInfo, getUsrid()에 로그인 아이디) */
    private static final String SESSION_MEMBER_INFO_KEY = "HNT_MEMBER_INFO";
    /**
     * true: 로그인 + 허용 목록(ALLOWED_USER_IDS) 모두 검사 / false: 로그인 여부만 검사.
     * 운영 세션 값 확인 중에는 false. 확인이 끝나면 true 로 바꿔 허용 목록을 다시 적용할 것.
     */
    private static final boolean ENFORCE_ALLOWLIST = false;
    private static final String PROMETHEUS_PATH = "/air/monitoring/prometheus";

    // 대시보드 조회 허용 사용자 아이디 (스냅샷에 예약/탑승객 정보가 있으므로 허용 목록 방식)
    // 아이디를 추가/삭제할 때는 마지막 항목 뒤에만 쉼표를 붙이지 않는다.
    private static final Set<String> ALLOWED_USER_IDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "A02347",
            "HG207",
            "HH659"
    )));

    private final FlightPriceSnapshotCbc flightPriceSnapshotCbc;

    /**
     * 스냅샷 조회 (수집일자 기준 KST 영업일, 양끝 포함)
     * - timezone은 Asia/Seoul만 허용한다. 생략하면 기존 호출과 동일하게 KST로 처리한다.
     */
    @GetMapping(value = "/air/flightPrice/snapshots", produces = JSON_UTF8)
    public ResponseEntity<String> getSnapshots(@RequestParam("date_start") String dateStart,
                               @RequestParam("date_end") String dateEnd,
                               @RequestParam(value = "timezone", required = false) String timezone) {
        final String usrId = requireLoginUser();
        log.info("[FLIGHT-PRICE-SNAPSHOT] 스냅샷 조회 usrId={} date_start={} date_end={}", usrId, dateStart, dateEnd);   // 조회 감사 로그
        if (timezone != null && !"Asia/Seoul".equals(timezone)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone은 Asia/Seoul만 지원합니다.");
        }
        FlightPriceSnapshotReqVo vo = new FlightPriceSnapshotReqVo();
        vo.setDateStart(dateStart);
        vo.setDateEnd(dateEnd);

        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.getSnapshots(vo));
    }

    /** Prometheus 즉시 조회 (instant query) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/query", produces = JSON_UTF8)
    public ResponseEntity<String> prometheusQuery(@RequestParam("query") String query,
                                  @RequestParam(value = "time", required = false) String time) {
        requireLoginUser();
        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.prometheusQuery(query, time));
    }

    /** Prometheus 범위 조회 (range query) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/query_range", produces = JSON_UTF8)
    public ResponseEntity<String> prometheusQueryRange(@RequestParam("query") String query,
                                       @RequestParam("start") String start,
                                       @RequestParam("end") String end,
                                       @RequestParam("step") String step) {
        requireLoginUser();
        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.prometheusQueryRange(query, start, end, step));
    }

    /** Prometheus 알림 규칙 조회 (type: alert, 생략 가능). record 조회는 허용하지 않는다. */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/rules", produces = JSON_UTF8)
    public ResponseEntity<String> prometheusRules(@RequestParam(value = "type", required = false) String type) {
        requireLoginUser();
        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.prometheusRules(type));
    }

    /**
     * 로그인 여부 확인. 통과하면 아이디를 반환한다.
     * - 아이디를 읽을 수 없으면 401 (원인 추적용 컨텍스트를 로그로 남긴다)
     * - ENFORCE_ALLOWLIST=true 일 때만 허용 목록을 추가로 검사하고, 목록에 없으면 403
     */
    private String requireLoginUser() {
        String source = "CoreUtil";
        String usrId = readUserIdFromCoreUtil();
        if (usrId == null || usrId.trim().isEmpty()) {
            // @RestController 경로는 CoreUtil 컨텍스트가 비어 있을 수 있어, 포털이 세션에 넣어둔 값을 직접 읽는다.
            usrId = readUserIdFromSession();
            source = "session";
        }
        if (usrId == null || usrId.trim().isEmpty()) {
            logAuthFailureContext();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "포털 로그인이 필요합니다.");
        }
        usrId = usrId.trim();
        if (ENFORCE_ALLOWLIST && !ALLOWED_USER_IDS.contains(usrId)) {
            log.warn("[FLIGHT-PRICE-SNAPSHOT] 접근 거부 usrId={}", usrId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 대시보드를 조회할 권한이 없습니다.");
        }
        log.info("[FLIGHT-PRICE-SNAPSHOT][AUTH] 로그인 확인 usrId={} source={}", usrId, source);   // 운영 추적용 (안정화 후 debug 로 낮출 것)
        return usrId;
    }

    /**
     * 로그인 확인 실패 시 원인 추적용 로그. 값(쿠키/세션 속성 내용)은 남기지 않고 이름/타입/존재 여부만 남긴다.
     * 서버 주소는 "서버 간 세션 미공유" 여부를 가리기 위한 용도.
     */
    private void logAuthFailureContext() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attrs == null ? null : attrs.getRequest();
            if (request == null) {
                log.warn("[FLIGHT-PRICE-SNAPSHOT][AUTH-FAIL] request 없음");
                return;
            }
            StringBuilder cookieNames = new StringBuilder();
            if (request.getCookies() != null) {
                for (Cookie cookie : request.getCookies()) {
                    cookieNames.append(cookie.getName()).append(',');
                }
            }
            HttpSession session = request.getSession(false);
            StringBuilder sessionAttrs = new StringBuilder();
            if (session != null) {
                for (String name : Collections.list(session.getAttributeNames())) {
                    Object v = session.getAttribute(name);
                    sessionAttrs.append(name).append('(').append(v == null ? "null" : v.getClass().getName()).append("),");
                }
            }
            log.warn("[FLIGHT-PRICE-SNAPSHOT][AUTH-FAIL] uri={} server={}:{} cookieNames={} session={} sessionAttrs={} remoteUser={}",
                    request.getRequestURI(), request.getLocalAddr(), request.getLocalPort(), cookieNames,
                    session == null ? "없음" : "있음", sessionAttrs, request.getRemoteUser() == null ? "없음" : "있음");
        } catch (RuntimeException e) {
            log.warn("[FLIGHT-PRICE-SNAPSHOT][AUTH-FAIL] 컨텍스트 로그 실패: {}", e.toString());
        }
    }

    private String readUserIdFromCoreUtil() {
        try {
            MemberInfo memberInfo = CoreUtil.getCertificationUtil().getMemberInfo();
            return memberInfo == null ? null : memberInfo.getUsrid();
        } catch (RuntimeException e) {
            // 프레임워크 컨텍스트가 없는 요청에서는 정상적으로 발생할 수 있다.
            log.debug("[FLIGHT-PRICE-SNAPSHOT] CoreUtil 회원정보 없음: {}", e.toString());
            return null;
        }
    }

    /** 서버 세션(HNT_MEMBER_INFO)에서 아이디를 읽는다. 세션이 없거나 타입이 다르면 null (= 거부). */
    private String readUserIdFromSession() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attrs == null ? null : attrs.getRequest();
            HttpSession session = request == null ? null : request.getSession(false); // 세션을 새로 만들지 않는다
            Object value = session == null ? null : session.getAttribute(SESSION_MEMBER_INFO_KEY);
            if (value == null) {
                log.warn("[FLIGHT-PRICE-SNAPSHOT] 로그인 사용자 확인 실패: session={} {}={}",
                        session == null ? "없음" : "있음", SESSION_MEMBER_INFO_KEY, "없음");
                return null;
            }
            if (!(value instanceof MemberInfo)) {
                log.warn("[FLIGHT-PRICE-SNAPSHOT] 세션 {} 타입 불일치: {}", SESSION_MEMBER_INFO_KEY, value.getClass().getName());
                return null;
            }
            return ((MemberInfo) value).getUsrid();
        } catch (RuntimeException e) {
            log.warn("[FLIGHT-PRICE-SNAPSHOT] 세션 회원정보 읽기 실패: {}", e.toString());
            return null;
        }
    }

    private ResponseEntity<String> jsonResponse(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType(JSON_UTF8))
                .cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(PrometheusUpstreamException.class)
    public ResponseEntity<String> prometheusError(PrometheusUpstreamException error) {
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.parseMediaType(JSON_UTF8))
                .cacheControl(CacheControl.noStore()).header("X-Monitoring-Error-Source", "prometheus")
                .body(error.getResponseBody());
    }

    // Spring 5.x 용. 운영 전역 예외 처리/SSO 필터의 응답 정책과 함께 확인한다.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> requestError(ResponseStatusException error) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("status", "error");
        body.put("errorType", error.getStatus() == HttpStatus.BAD_REQUEST ? "bad_data"
                : error.getStatus() == HttpStatus.GATEWAY_TIMEOUT ? "timeout" : "proxy_error");
        body.put("error", error.getReason() == null ? "조회에 실패했습니다." : error.getReason());
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.parseMediaType(JSON_UTF8))
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
