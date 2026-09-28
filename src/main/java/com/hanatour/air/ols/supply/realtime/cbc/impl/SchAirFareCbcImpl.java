package com.hanatour.air.ols.supply.realtime.cbc.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.gson.Gson;
import com.hanatour.air.cmn.api.cmn.rule.farConfRule.bc.vo.*;
import com.hanatour.air.cmn.api.cmn.rule.farConfRule.ts.*;
import com.hanatour.air.cmn.api.cmn.vo.BizComVo;
import com.hanatour.air.cmn.common.ApiException;
import com.hanatour.air.cmn.common.hist.ec.qc.vo.ComComDtlCQcVo;
import com.hanatour.air.cmn.common.utils.DateTimeUtils;
import com.hanatour.air.cmn.common.worker.context.WorkerContext;
import com.hanatour.air.cmn.common.worker.utils.WorkerContextResult;
import com.hanatour.air.cmn.webclient.RestServiceClient;
import com.hanatour.air.cmn.webclient.ServiceEndpoint;
import com.hanatour.air.ols.common.bc.ComComDtlCBc;
import com.hanatour.air.ols.common.constants.RealtimeConst;
import com.hanatour.air.ols.reserve.airMgr.air.bc.MessageUtils;
import com.hanatour.air.ols.reserve.airMgr.air.bc.vo.AirSrchRsltApiVo;
import com.hanatour.air.ols.supply.realtime.cbc.SchAirFareCbc;
import com.hanatour.air.ols.supply.realtime.cbc.vo.*;
import com.hanatour.common.cmn.CoreUtil;
import com.hanatour.common.cmn.restclient.vo.Header;
import hntframe.run.common.exception.BusinessException;
import hntframe.run.common.util.NumberUtil;
import hntframe.run.common.util.StringUtil;
import hntframe.run.common.util.date.DateUtil;
import hntframe.run.oltp.ext.integration.annotation.ServiceMapping;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 운임조회 CBC Impl
 * 주의사항 1
 * SchAirFareResultVo 의 멤버변수명과 넥사크로 dataSet:ds_fareList column의 명은 동일하게 처리해야한다
 * 해당 명을 이용해서 개발했기 때문에 주의필요
 * 필터링 항목을 추가하려면 FltrType enum에 항목을 추가하고 넥사크로의 const에도 동일하게 추가해야한다.
 */
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class SchAirFareCbcImpl implements SchAirFareCbc {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final ComComDtlCBc comComDtlCBc;
	private final RltmFeeRuleTs rltmFeeRuleTs;						// 수수료
	private final RltmPayTlAplTs rltmPayTlAplTs;						// 결제TL
	private final RltmSuplCmsnTs rltmSuplCmsnTs;						// 공급사커미션
	private final RltmAgtCmsnTs rltmAgtCmsnTs;						// 대리점커미션
	private final RltmAtmtIsuePsblYnRuleTs rltmAtmtIsuePsblYnRuleTs;	// 자동발권
	private final RltmFareRuleTs rltmFareRuleTs;						// 운임규정
	private final RestServiceClient restServiceClient;

	private enum FltrType {
		  SPLY_CD("splyCd")
		, VIA_CNT("viaCnt")
		, AL_COMPLEX("alComplex")
		, TKT_AL_CODE("tktAlCode")
		, MKT_AL_CODES("mktAlCode")
		, OPR_AL_CODES("oprAlCode")
		, ADT_T_AMT("adtTamt")
		, TOT_TIME("totTime")
		, STOP_TIME("stopTime")
		, CABIN_COMPLEX("cabinComplex")
		, CABIN_TYPES("cabinType")
		, PASN_TYPES("pasnTypes")
		, FARE_TYPES("fareType")
		, ACCT_CODES("acctCode")
		, IMDT_PAY_PSBL_YN("imdtPayPsblYn")
		, FIX_FAR_YN("fixFarYn")
		, ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN("isueAirlRuleFarExlsTrgtYn")
		, MCT_RULE_FAR_EXLS_TRGT_YN("MCTRuleFarExlsTrgtYn")
		, CARD_PROM_IDS("cardPromIds")
		, GNRL_EVENT_CODE("gnrlEventCode")
		, DC_EVENT_CODE("dcEventCode")
		, AIR_FAR_COMB_YN("airFarCombYn");

		private String fltrFld;
		private String getFltrFld(){
			return fltrFld;
		}
		private FltrType(String fltrFld) {
			this.fltrFld = fltrFld;
		}
	}
	
	private final String ALL_TAP = "ALL";

	/**
	 * 운임조회
	 */
	@Override
	@ServiceMapping(value = "/air/ols/supply/realtime/cbc/schairfare/getAirFareList/v1.00", method = RequestMethod.POST)
	public SchAirFareCbcOutVo getAirFareList(SchAirFareCbcInVo schAirFareCbcInVo){
		String resJsonStr = "";

		String jsonString = "";
		jsonString = this.createReqJsonObj(schAirFareCbcInVo.getSchAirFareSearchVo());
		resJsonStr = this.callApiService(jsonString);
		
		ComComDtlCQcVo comComDtlCQcVo = new ComComDtlCQcVo();
		// 좌석등급명 list 가져오기
		comComDtlCQcVo.setComBscCd("SEAT_GRAD_CD");
		List<ComComDtlCQcVo> seatGradList = comComDtlCBc.getComCdList(comComDtlCQcVo);

		// 운임유형명 list 가져오기
		comComDtlCQcVo.setComBscCd("RI_FAR_TYPE_CD");
		List<ComComDtlCQcVo> fareTypeList = comComDtlCBc.getComCdList(comComDtlCQcVo);
		
		SchAirFareCbcOutVo schAirFareCbcOutVo = new SchAirFareCbcOutVo();
		this.makeResponse(resJsonStr, schAirFareCbcOutVo, seatGradList, fareTypeList);
		
		schAirFareCbcOutVo.setInputJsonString(jsonString);	 // 20200403 : 운임결과 없을 경우 조회에 사용된 input용 json을 포스트맨에서 조회하기위해 리턴값 추가

		return schAirFareCbcOutVo;
	}
	
	/**
	 * 운임조회
	 */
	@Override
	@ServiceMapping(value = "/air/ols/supply/realtime/cbc/schairfare/getAirFareChangeList/v1.00", method = RequestMethod.POST)
	public SchAirFareCbcOutVo getAirFareChangeList(SchAirFareChangeCbcInVo schAirFareChangeCbcInVo){
		//-------------------------------
		// 20191113 : 소켓타임아웃 - 체크용 시간추가
		//-------------------------------
		String sCallApiStartTime = "";
		String sCallApiEndTime   = "";
		String sMakeStartTime    = "";
		String sMakeEndTime      = "";
		//-------------------------------

		logger.debug("===========================================");
		logger.debug("yt getAirFareChangeList  ~~ start ");
		logger.debug("===========================================");
		sCallApiStartTime = DateTimeUtils.getSystemCurrentDateTime();
		
		String resJsonStr = "";
		String jsonString = "";

		SchAirFareChangeSearchVo schAirFareChangeSearchVo = schAirFareChangeCbcInVo.getSchAirFareChangeSearchVo();
		logger.info("캐시사용여부========== "+ schAirFareChangeSearchVo.getCacheSearchYn());

		jsonString = this.createReqJsonObj2(schAirFareChangeSearchVo);
		resJsonStr = this.callApiService(jsonString);
		sCallApiEndTime   = DateTimeUtils.getSystemCurrentDateTime();
		
		SchAirFareCbcOutVo schAirFareCbcOutVo = new SchAirFareCbcOutVo();
		logger.debug("== makeResponse ===========================");
		
		ComComDtlCQcVo comComDtlCQcVo = new ComComDtlCQcVo();
		// 좌석등급명 list 가져오기
		comComDtlCQcVo.setComBscCd("SEAT_GRAD_CD");
		List<ComComDtlCQcVo> seatGradList = comComDtlCBc.getComCdList(comComDtlCQcVo);

		// 운임유형명 list 가져오기
		comComDtlCQcVo.setComBscCd("RI_FAR_TYPE_CD");
		List<ComComDtlCQcVo> fareTypeList = comComDtlCBc.getComCdList(comComDtlCQcVo);
		
		sMakeStartTime    = DateTimeUtils.getSystemCurrentDateTime();
		this.makeResponse(resJsonStr, schAirFareCbcOutVo, seatGradList, fareTypeList);
		sMakeEndTime      = DateTimeUtils.getSystemCurrentDateTime();
		logger.debug("===========================================");
		logger.debug("yt getAirFareChangeList  ~~ end ");
		logger.debug("===========================================");
		
		//-------------------------------
		// 20191113 : 소켓타임아웃 - 체크용 시간추가
		//-------------------------------
		schAirFareCbcOutVo.setCallApiStartTime(sCallApiStartTime );
		schAirFareCbcOutVo.setCallApiEndTime(sCallApiEndTime);
		schAirFareCbcOutVo.setMakeStartTime(sMakeStartTime);
		schAirFareCbcOutVo.setMakeEndTime(sMakeEndTime);
		//-------------------------------
		
		schAirFareCbcOutVo.setInputJsonString(jsonString);	 // 20200403 : 운임결과 없을 경우 조회에 사용된 input용 json을 포스트맨에서 조회하기위해 리턴값 추가
		
		return schAirFareCbcOutVo;
	}

	public SchAirFareCbcOutVo makeResponse(String resJsonStr, SchAirFareCbcOutVo schAirFareCbcOutVo, List<ComComDtlCQcVo> seatGradList, List<ComComDtlCQcVo> fareTypeList){
		List<SchAirFareResultVo>       fareList     = new ArrayList<SchAirFareResultVo>();					//운임조회 List
		List<SchAirFareResultFilterVo> fareFltrList = new ArrayList<SchAirFareResultFilterVo>();			//Filter List
		Map<String, String>                   splyCdMap   = new HashMap<String, String>();					//공급코드 Map
		Map<String, SchAirFareResultFilterVo> fareFltrMap = new HashMap<String, SchAirFareResultFilterVo>();//Filter Map
		
		logger.debug("yt toJsonNode - START ============================ ");			  
		JsonNode node = MessageUtils.toJsonNode(resJsonStr);
		logger.debug("yt toJsonNode - END ============================ ");			  
		
		//------------------------------
		// Set Response(JSON) 
		//------------------------------
		List<SchAirFareResponseBodyVo>          ds_jsonBody         = new ArrayList<SchAirFareResponseBodyVo>();           // body
		List<SchAirFareResponseBizComVo>        ds_jsonBizCom       = new ArrayList<SchAirFareResponseBizComVo>();	       //      ├ bizCom
		List<SchAirFareResponseFarLstVo>        ds_jsonFarLst       = new ArrayList<SchAirFareResponseFarLstVo>();         //      └ farLst
		List<SchAirFareResponseFarPrcLstVo>     ds_jsonFarPrcLst    = new ArrayList<SchAirFareResponseFarPrcLstVo>();      //        farLst ┬ farPrcLst
		List<SchAirFareResponseFeeDtlVo>        ds_jsonFeeDtl       = new ArrayList<SchAirFareResponseFeeDtlVo>();         //               ├ feeDtl
		List<SchAirFareResponseItnrLstVo>       ds_jsonItnrLst      = new ArrayList<SchAirFareResponseItnrLstVo>();        //               └ itnrLst
		List<SchAirFareResponseFltLstVo>        ds_jsonFltLst       = new ArrayList<SchAirFareResponseFltLstVo>();         //                 itnrLst ┬ fltLst
		List<SchAirFareResponseFltPrcLstVo>     ds_jsonFltPrcLst    = new ArrayList<SchAirFareResponseFltPrcLstVo>();      //                         ├ fltPrcLst
		List<SchAirFareResponseFreeBaggLstVo>   ds_jsonFreeBaggLst  = new ArrayList<SchAirFareResponseFreeBaggLstVo>();    //                         ├ freeBaggLst
		List<SchAirFareResponseHidStopLstVo>    ds_jsonhidStopLst   = new ArrayList<SchAirFareResponseHidStopLstVo>();     //                         ├ hidStopLst
		//------------------------------
		// Set Response(JSON) - body
		//------------------------------
		String sPollingId     = StringUtil.nullConvert(node.path("pollingId"    ).textValue());		// polling ID
		String sSearchType    = StringUtil.nullConvert(node.path("searchType"   ).textValue());		// 조회방식
		String sSearchEof     = StringUtil.nullConvert(node.path("searchEof"    ).textValue());		// 조회요청판단구분
		long lTotRows         =                        node.path("totRows"      ).asLong(0);		// 전체운임목록카운트
		String sRirtDvCd      = StringUtil.nullConvert(node.path("rirtDvCd"     ).textValue());		// RT/RI 요청타입
		String sCacheSearchYn = StringUtil.nullConvert(node.path("cacheSearchYn").textValue());		// 캐시조회여부
		String sItnrTypeCd    = StringUtil.nullConvert(node.path("itnrTypeCd"   ).textValue());		// 여정유형코드
		String sSeatGradCd    = StringUtil.nullConvert(node.path("seatGradCd"   ).textValue());		// 좌석구분코드
		String sIsueScheDt    = StringUtil.nullConvert(node.path("isueScheDt"   ).textValue());		// 발권예정일
		String sNonStopOnly   = StringUtil.nullConvert(node.path("nonStopOnly"  ).textValue());		// 직항여부

		SchAirFareResponseBodyVo responseBodyVo = new SchAirFareResponseBodyVo();		
		responseBodyVo.setPollingId(sPollingId);
		responseBodyVo.setPollingId(sPollingId);
		responseBodyVo.setSearchType(sSearchType);
		responseBodyVo.setSearchEof(sSearchEof);
		responseBodyVo.setTotRows(lTotRows);
		responseBodyVo.setRirtDvCd(sRirtDvCd);
		responseBodyVo.setCacheSearchYn(sCacheSearchYn);
		responseBodyVo.setItnrTypeCd(sItnrTypeCd);
		responseBodyVo.setSeatGradCd(sSeatGradCd);
		responseBodyVo.setIsueScheDt(sIsueScheDt);
		responseBodyVo.setNonStopOnly(sNonStopOnly);
		
		ds_jsonBody.add(responseBodyVo);
		//------------------------------
		SchAirFareResponseFarLstVo responseFarLstVo;
		SchAirFareResponseFarPrcLstVo responseFarPrcLstVo;
		SchAirFareResponseItnrLstVo responseItnrLstVo;
		SchAirFareResponseFltLstVo responseFltLstVo;
		SchAirFareResponseFltPrcLstVo responseFltPrcLstVo;
		SchAirFareResponseFreeBaggLstVo responseFreeBaggLstVo;
		SchAirFareResponseHidStopLstVo responseHidStopLstVoo;
		//------------------------------
		
		String gdsItnrTypeCd = "";	// 룰셋여정타입
		String apiSupCode = "";
		String newSplyCd  = "";

		long farLstCnt = 0;
		if(node != null && node.path("farLst") != null) {
			farLstCnt = node.path("farLst").size();	// 화면에서 운임 건수 없을 경우 Alert 띄워주기 위해 추가
		}

		boolean bIsDebug = false;
		//------------------------------
		// Set Response(JSON) - bizCom
		//------------------------------
		JsonNode bizComNode = node.path("bizCom");
		if(null != bizComNode) {
			SchAirFareResponseBizComVo responseBizComVo = new SchAirFareResponseBizComVo();		
			responseBizComVo = new Gson().fromJson(bizComNode.toString(), SchAirFareResponseBizComVo.class);			
				
			ds_jsonBizCom.add(responseBizComVo);
			
			bIsDebug = bizComNode.path("isDebug").asBoolean(false);			
		}
		//------------------------------
		
		StringBuilder sbDefaultInfoFm;	// 기본보기 정보
		StringBuilder sbMoreViewInfoFm;	// 더보기 정보

		StringBuilder sbMktAlCodes;
        StringBuilder sbEqmtNames;
        StringBuilder sbOprAlCodes;
		StringBuilder sbPasnTypes ; 
		StringBuilder sbCabinTypes; 
		StringBuilder sbFareTypes ; 
		StringBuilder sbAcctCodes ; 
		
		StringBuilder sbViaInfo     ;
		StringBuilder sbAlInfo      ;
		StringBuilder sbFltNoInfo   ;
		StringBuilder sbFltSchdInfo ;
		StringBuilder sbFbInfo      ;
		StringBuilder sbFareTypeInfo;
		 
		StringBuilder sbSiteRuleGnrlAdt;
		StringBuilder sbSiteRuleGnrlChd;
		StringBuilder sbSiteRuleGnrlInf;
		StringBuilder sbSiteRuleDtcmAdt;
		StringBuilder sbSiteRuleDtcmChd;
		StringBuilder sbSiteRuleDtcmInf;
		
		StringBuilder sbAdtGnrlDcAmtInfoTemp;
		StringBuilder sbChdGnrlDcAmtInfoTemp;
		StringBuilder sbInfGnrlDcAmtInfoTemp;
		StringBuilder sbAdtDtcmDcAmtInfoTemp;
		StringBuilder sbChdDtcmDcAmtInfoTemp;
		StringBuilder sbInfDtcmDcAmtInfoTemp;					
		StringBuilder sbAdtGnrlDcAmtInfo;
		StringBuilder sbChdGnrlDcAmtInfo;
		StringBuilder sbInfGnrlDcAmtInfo;
		StringBuilder sbAdtDtcmDcAmtInfo;
		StringBuilder sbChdDtcmDcAmtInfo;
		StringBuilder sbInfDtcmDcAmtInfo;
		
		StringBuilder sbMktFltNoInfo;	//항공편명-결과내검색용
		StringBuilder sbBookClassInfo;	//항공편명-결과내검색용
		StringBuilder sbFareBasisInfo;	//항공편명-결과내검색용
		StringBuilder sbGnrlEventCdDp;	//화면표시용
		StringBuilder sbGnrlAdtSpclColtMlgAmt;
		StringBuilder sbGnrlChdSpclColtMlgAmt;
		StringBuilder sbDtcmAdtSpclColtMlgAmt;
		StringBuilder sbDtcmChdSpclColtMlgAmt;
		StringBuilder sbGnrlCardPromId;
		StringBuilder sbGnrlCardPromEventCd;
		StringBuilder sbGnrlCardNm;
		StringBuilder sbGnrlCardDcInfo;
		StringBuilder sbGnrlCardDcAplAmt;
		StringBuilder sbGnrlCardDcTotalAmt;
		StringBuilder sbDtcmCardPromId;
		StringBuilder sbDtcmCardPromEventCd;
		StringBuilder sbDtcmCardNm;
		StringBuilder sbDtcmCardDcInfo;
		StringBuilder sbDtcmCardDcAplAmt;
		StringBuilder sbDtcmCardDcTotalAmt;
		StringBuilder sbCardGnrlAdt;
		StringBuilder sbCardGnrlChd;
		StringBuilder sbCardGnrlInf;
		StringBuilder sbCardDtcmAdt;
		StringBuilder sbCardDtcmChd;
		StringBuilder sbCardDtcmInf;
		StringBuilder sbIsueFeeGnrlAdt;
		StringBuilder sbIsueFeeGnrlChd;
		StringBuilder sbIsueFeeGnrlInf;
		StringBuilder sbIsueFeeDtcmAdt;
		StringBuilder sbIsueFeeDtcmChd;
		StringBuilder sbIsueFeeDtcmInf;
		StringBuilder sbSplyInfo;
		StringBuilder sbAgtCmsnGnrlTitle;
		StringBuilder sbAgtCmsnDtcmTitle;
		StringBuilder sbAgtCmsnGnrlAdt;
		StringBuilder sbAgtCmsnGnrlChd;
		StringBuilder sbAgtCmsnGnrlInf;
		StringBuilder sbAgtCmsnDtcmAdt;
		StringBuilder sbAgtCmsnDtcmChd;
		StringBuilder sbAgtCmsnDtcmInf;
		StringBuilder sbTmpFareBscInfo;				
		StringBuilder sbEtcInfo;				
		StringBuilder sbFareBscInfo;
		StringBuilder sbTempAgtCmsnGnrlStr;
		StringBuilder sbTempAgtCmsnDtcmStr;
		
		int i=0;
		for(JsonNode farLstNode : node.path("farLst")) {	//운임리스트
			logger.debug("yt node - for i=" + i++);
			String sFareId                    = StringUtil.nullConvert(farLstNode.path("fareId"                   ).textValue());		// ROW KEY - 운임ID
			String sIsueAirlRuleFarExlsTrgtYn = StringUtil.nullConvert(farLstNode.path("isueAirlRuleFarExlsTrgtYn").textValue());
			String sMCTRuleFarExlsTrgtYn      = StringUtil.nullConvert(farLstNode.path("MCTRuleFarExlsTrgtYn"     ).textValue());
			String sFarExlsResnCd      		  = StringUtil.nullConvert(farLstNode.path("farExlsResnCd"     ).textValue());

			String sAirFarCombYn = farLstNode.path("airFarCombYn").asText();
			
			//------------------------------
			// Set Response(JSON) - farLst
			//------------------------------
			String sParentRowKey1 = sFareId;		// 상위 키 = 운임ID

			if("Y".equals(sAirFarCombYn)) { //편도결합여부
				for (JsonNode farVoLst : farLstNode.path("farVoLst")) {
					responseFarLstVo = new Gson().fromJson(farVoLst.toString(), SchAirFareResponseFarLstVo.class);
					responseFarLstVo.setOrgJson(farVoLst.toString());		// json원문
					ds_jsonFarLst.add(responseFarLstVo);
				}
			} else {
				responseFarLstVo = new Gson().fromJson(farLstNode.toString(), SchAirFareResponseFarLstVo.class);
				responseFarLstVo.setOrgJson(farLstNode.toString());		// json원문
				ds_jsonFarLst.add(responseFarLstVo);
			}
			//------------------------------

			if(farLstNode.get("splyCd") != null) {	//공급코드 존재여부확인
				gdsItnrTypeCd = farLstNode.path("gdsItnrTypeCd").textValue();	// 룰셋여정타입
				apiSupCode    = farLstNode.path("apiSupCode").textValue();
				newSplyCd     = farLstNode.path("splyCd"    ).textValue();

				if(!"V".equals(apiSupCode) && !StringUtil.isEmpty(newSplyCd)){	//V : 인벤토리 타입 제외
					if(!splyCdMap.containsKey(newSplyCd)){
						splyCdMap.put(newSplyCd, newSplyCd);
					}

					String alComplexChk = "";
					String tktAlCode = "";
					String tktAlName = "";
					String pasnType = "";

					sbMktAlCodes = new StringBuilder("");
                    sbEqmtNames = new StringBuilder("");
                    sbOprAlCodes = new StringBuilder("");
					sbPasnTypes  = new StringBuilder("");
					sbCabinTypes = new StringBuilder("");
					sbFareTypes  = new StringBuilder("");
					sbAcctCodes  = new StringBuilder("");

					sbMktFltNoInfo = new StringBuilder("");	//항공편명-결과내검색용
					sbBookClassInfo = new StringBuilder("");	//항공편명-결과내검색용
					sbFareBasisInfo = new StringBuilder("");	//항공편명-결과내검색용

					sbViaInfo      = new StringBuilder("");
					sbAlInfo       = new StringBuilder("");
					sbFltNoInfo    = new StringBuilder("");
					sbFltSchdInfo  = new StringBuilder("");
					sbFbInfo       = new StringBuilder("");
					sbFareTypeInfo = new StringBuilder("");

					sbAdtGnrlDcAmtInfo = new StringBuilder("");
					sbChdGnrlDcAmtInfo = new StringBuilder("");
					sbInfGnrlDcAmtInfo = new StringBuilder("");
					sbAdtDtcmDcAmtInfo = new StringBuilder("");
					sbChdDtcmDcAmtInfo = new StringBuilder("");
					sbInfDtcmDcAmtInfo = new StringBuilder("");

					//일반
					sbGnrlCardPromId      = new StringBuilder("");
					sbGnrlCardPromEventCd = new StringBuilder("");
					sbGnrlCardNm          = new StringBuilder("");
					sbGnrlCardDcInfo      = new StringBuilder("");
					sbGnrlCardDcAplAmt    = new StringBuilder("");
					sbGnrlCardDcTotalAmt  = new StringBuilder("");

					//닷컴
					sbDtcmCardPromId       = new StringBuilder("");
					sbDtcmCardPromEventCd  = new StringBuilder("");
					sbDtcmCardNm           = new StringBuilder("");
					sbDtcmCardDcInfo       = new StringBuilder("");
					sbDtcmCardDcAplAmt     = new StringBuilder("");
					sbDtcmCardDcTotalAmt   = new StringBuilder("");

					//필터링에 사용할 최종금액 설정
					long adtTamt = 0;
					long chdTamt = 0;
					long infTamt = 0;

					int totFltTime = 0;			//총소요시간
					int totStopTime = 0;		//총환승시간

					int nMaxViaCnt = 0;	//경유횟수

					String isExistCardGnrlInfo = "";
					String isExistCardDtcmInfo = "";

					String cabinComplexChk = "";

					String gnrlEventCds  = "";	//필터용

					StringBuilder sbCardPromIds = new StringBuilder("");

					String dcEventCd = "";
					String imdtPayPsblYn = "";
					String fixFarYn = "";

					String fareBscInfo = "";
					String moreTxt = "";

					sbDefaultInfoFm = new StringBuilder("");	// 기본보기 정보
					sbMoreViewInfoFm = new StringBuilder("");	// 더보기 정보

					//노출제어 텍스트
					String sFarExlsResnText = "A".equals(sFarExlsResnCd) ? "당일운임제외" : "B".equals(sFarExlsResnCd) ? "중복운임제외" : "C".equals(sFarExlsResnCd) ? "중복편명제외" : "";

					if("Y".equals(sAirFarCombYn)) { //편도결합여부
						int farIndex = 0;

						for (JsonNode farVoLst : farLstNode.path("farVoLst")) {
							//------------------------------
							// 실시간 운임 프라이싱 - 성인, 아동, 유아 count하여 화면 출력시 출력여부 참조
							//------------------------------
							int nCntAgeAdt  = 0;
							int nCntAgeChd  = 0;
							int nCntAgeInf  = 0;
							boolean bCntAdt = false;
							boolean bCntChd = false;
							boolean bCntInf = false;

							String sBasicPasnTypeNmAdt = "ADT ";
							String sBasicPasnTypeNmChd = "CHD ";
							String sBasicPasnTypeNmInf = "INF ";

							for(JsonNode farPrcLstNode : farVoLst.path("farPrcLst")) {
								//------------------------------
								// Set Response(JSON) - farPrcLst
								//------------------------------

								String finds = sParentRowKey1;
								int idx = finds.indexOf("!");
								String ans =  finds.substring(0,idx);				//가는편
								String ans2 =  finds.substring(idx+1);	//오는편

								if(farIndex==0){
									responseFarPrcLstVo = new Gson().fromJson(farPrcLstNode.toString(), SchAirFareResponseFarPrcLstVo.class);
									responseFarPrcLstVo.setOrgJson(farPrcLstNode.toString());	// json원문
									responseFarPrcLstVo.setParentRowKey(ans);		// 상위 키

									ds_jsonFarPrcLst.add(responseFarPrcLstVo);
								}else{
									responseFarPrcLstVo = new Gson().fromJson(farPrcLstNode.toString(), SchAirFareResponseFarPrcLstVo.class);
									responseFarPrcLstVo.setOrgJson(farPrcLstNode.toString());	// json원문
									responseFarPrcLstVo.setParentRowKey(ans2);		// 상위 키

									ds_jsonFarPrcLst.add(responseFarPrcLstVo);
								}

								//------------------------------

								String sPasnType = StringUtil.nullConvert(farPrcLstNode.path("pasnType").asText());
								String sAgeType  = StringUtil.nullConvert(farPrcLstNode.path("ageType" ).asText());

								// ageType 기준으로 ADT/CHD/INF 표기
								if("ADT".equals(sAgeType)) {
									nCntAgeAdt++;
									// ageType과 pasnType이 다를 경우 pasnType을 괄호로 명기
									if(!sPasnType.equals(sAgeType)) sBasicPasnTypeNmAdt = sAgeType + " <fc v='red'>(PTC:"+sPasnType+")</fc> ";
								} else if("CHD".equals(sAgeType)) {
									nCntAgeChd++;
									// ageType과 pasnType이 다를 경우 pasnType을 괄호로 명기
									if(!sPasnType.equals(sAgeType)) sBasicPasnTypeNmChd = sAgeType + " <fc v='red'>(PTC:"+sPasnType+")</fc> ";
								} else if("INF".equals(sAgeType)) {
									nCntAgeInf++;
									// ageType과 pasnType이 다를 경우 pasnType을 괄호로 명기
									if(!sPasnType.equals(sAgeType)) sBasicPasnTypeNmInf = sAgeType + " <fc v='red'>(PTC:"+sPasnType+")</fc> ";
								}
							}
							if(nCntAgeAdt > 0) bCntAdt = true;
							if(nCntAgeChd > 0) bCntChd = true;
							if(nCntAgeInf > 0) bCntInf = true;
							//------------------------------

							newSplyCd     = farVoLst.path("splyCd"    ).textValue();

							//통합탭-필터설정 : 공급코드
							this.setFilterMap(fareFltrMap, ALL_TAP, newSplyCd, newSplyCd, 0L, FltrType.SPLY_CD);

							this.setCombFilterMap(fareFltrMap, newSplyCd, sAirFarCombYn , "결합" , 0L, FltrType.AIR_FAR_COMB_YN);

							//운임 계산용 금액/포맷문자열 - 편도결합/비결합 공통 로직은 computeFeeAmountTexts()로 추출
							FeeAmountTexts feeAmountTexts = this.computeFeeAmountTexts(farVoLst);
							long gnrlAdtEtcAmtL = feeAmountTexts.gnrlAdtEtcAmtL;
							long gnrlChdEtcAmtL = feeAmountTexts.gnrlChdEtcAmtL;
							long gnrlInfEtcAmtL = feeAmountTexts.gnrlInfEtcAmtL;
							long dtcmAdtEtcAmtL = feeAmountTexts.dtcmAdtEtcAmtL;
							long dtcmChdEtcAmtL = feeAmountTexts.dtcmChdEtcAmtL;
							long dtcmInfEtcAmtL = feeAmountTexts.dtcmInfEtcAmtL;
							String adtQchrgAmtStr = feeAmountTexts.adtQchrgAmtStr;
							String chdQchrgAmtStr = feeAmountTexts.chdQchrgAmtStr;
							String infQchrgAmtStr = feeAmountTexts.infQchrgAmtStr;
							String adtFuelExchgAmtStr = feeAmountTexts.adtFuelExchgAmtStr;
							String chdFuelExchgAmtStr = feeAmountTexts.chdFuelExchgAmtStr;
							String infFuelExchgAmtStr = feeAmountTexts.infFuelExchgAmtStr;
							String adtTaxAmtStr = feeAmountTexts.adtTaxAmtStr;
							String chdTaxAmtStr = feeAmountTexts.chdTaxAmtStr;
							String infTaxAmtStr = feeAmountTexts.infTaxAmtStr;
							String gnrlAdtIsueFeeAmtStr = feeAmountTexts.gnrlAdtIsueFeeAmtStr;
							String gnrlChdIsueFeeAmtStr = feeAmountTexts.gnrlChdIsueFeeAmtStr;
							String gnrlInfIsueFeeAmtStr = feeAmountTexts.gnrlInfIsueFeeAmtStr;
							String dtcmAdtIsueFeeAmtStr = feeAmountTexts.dtcmAdtIsueFeeAmtStr;
							String dtcmChdIsueFeeAmtStr = feeAmountTexts.dtcmChdIsueFeeAmtStr;
							String dtcmInfIsueFeeAmtStr = feeAmountTexts.dtcmInfIsueFeeAmtStr;

							//################ 기본 요금정보 #############################
							long chdBscAmtL = farVoLst.path("chdBscAmt").asLong(0);		//계산용-아동기본요금
							long infBscAmtL = farVoLst.path("infBscAmt").asLong(0);		//계산용-유아기본요금
							long chdBscTotalAmt = chdBscAmtL + gnrlChdEtcAmtL;	//아동기본 최종요금
							long infBscTotalAmt = infBscAmtL + gnrlInfEtcAmtL;	//유아기본 최종요금

							//사이트룰(판매룰/할인이벤트) 텍스트 조립 - 편도결합/비결합 공통 로직은 buildSiteRuleTexts()로 추출
							SiteRuleTexts siteRuleTexts = this.buildSiteRuleTexts(farVoLst);
							sbSiteRuleGnrlAdt = siteRuleTexts.sbSiteRuleGnrlAdt;
							sbSiteRuleGnrlChd = siteRuleTexts.sbSiteRuleGnrlChd;
							sbSiteRuleGnrlInf = siteRuleTexts.sbSiteRuleGnrlInf;
							sbSiteRuleDtcmAdt = siteRuleTexts.sbSiteRuleDtcmAdt;
							sbSiteRuleDtcmChd = siteRuleTexts.sbSiteRuleDtcmChd;
							sbSiteRuleDtcmInf = siteRuleTexts.sbSiteRuleDtcmInf;
							dcEventCd = siteRuleTexts.dcEventCd;
							String dcEventNm = siteRuleTexts.dcEventNm;
							//일반할인요금정보 + 필터용 최종금액 - 편도결합/비결합 공통 로직은 computeGeneralDiscountFareTexts()로 추출
							GeneralDiscountFareTexts generalDiscountFareTexts = this.computeGeneralDiscountFareTexts(farVoLst, feeAmountTexts);
							sbAdtGnrlDcAmtInfoTemp = generalDiscountFareTexts.sbAdtGnrlDcAmtInfoTemp;
							sbChdGnrlDcAmtInfoTemp = generalDiscountFareTexts.sbChdGnrlDcAmtInfoTemp;
							sbInfGnrlDcAmtInfoTemp = generalDiscountFareTexts.sbInfGnrlDcAmtInfoTemp;
							sbAdtDtcmDcAmtInfoTemp = generalDiscountFareTexts.sbAdtDtcmDcAmtInfoTemp;
							sbChdDtcmDcAmtInfoTemp = generalDiscountFareTexts.sbChdDtcmDcAmtInfoTemp;
							sbInfDtcmDcAmtInfoTemp = generalDiscountFareTexts.sbInfDtcmDcAmtInfoTemp;
							adtTamt = generalDiscountFareTexts.adtTamt;
							chdTamt = generalDiscountFareTexts.chdTamt;
							infTamt = generalDiscountFareTexts.infTamt;

							cabinComplexChk = "N";	//좌석결합여부 체크용
							alComplexChk = "N";		//항공사결합여부 체크용
							Map<String, String> cabinMap = new HashMap<String, String>();
							Map<String, String> mktAlMap = new HashMap<String, String>();

							String viaCnt = "";	//경유횟수
							nMaxViaCnt = 0;	//경유횟수
							int nTempViaCnt = 0;//경유횟수
							String viaStr = "";	//경유횟수텍스트
							String cabinType = "";		//좌석등급
							String bookClass = "";		//부킹클래스
							String fareBasis = "";		//fareBasis
							String tktDesg = "";

							String fareType = "";		//운임유형
							totFltTime = 0;			//총소요시간
							totStopTime = 0;		//총환승시간

							int j=0;
							logger.debug("yt node.itnrLst ---------");
							//################### 여정정보 ##############################################
							for(JsonNode itnrLstNode : farVoLst.path("itnrLst")) {
								//------------------------------
								// Set Response(JSON) - itnrLst
								//------------------------------
								String sItnrSeq = StringUtil.nullConvert(itnrLstNode.path("itnrSeq").toString());	// ROW KEY - 여정순번

								String finds = sParentRowKey1;
								int idx = finds.indexOf("!");
								String ans =  finds.substring(0,idx);
								String ans2 =  finds.substring(idx+1);

								String sParentRowKey2 = "";

								if(farIndex==0){
									responseItnrLstVo = new Gson().fromJson(itnrLstNode.toString(), SchAirFareResponseItnrLstVo.class);
									responseItnrLstVo.setOrgJson(itnrLstNode.toString());	// json원문
									responseItnrLstVo.setParentRowKey(ans);		// 상위 Row 키
									responseItnrLstVo.setRowKey(ans +":"+ sItnrSeq);	// 현재 Row 키
									ds_jsonItnrLst.add(responseItnrLstVo);

									sParentRowKey2 = ans + ":" + sItnrSeq;
								}else{
									responseItnrLstVo = new Gson().fromJson(itnrLstNode.toString(), SchAirFareResponseItnrLstVo.class);
									responseItnrLstVo.setOrgJson(itnrLstNode.toString());	// json원문
									responseItnrLstVo.setParentRowKey(ans2);		// 상위 Row 키
									responseItnrLstVo.setRowKey(ans2 +":"+ sItnrSeq);	// 현재 Row 키
									ds_jsonItnrLst.add(responseItnrLstVo);

									sParentRowKey2 = ans2 + ":" + sItnrSeq;
								}

								//------------------------------

								logger.debug("yt node.itnrLst - for j=" + j++);
								viaCnt = itnrLstNode.path("viaCnt").asText();
								nTempViaCnt = itnrLstNode.path("viaCnt").asInt(0);
								if(Integer.valueOf(viaCnt) > 1) {	//경유횟수:viaCnt=1은 직항을 의미
									int vi = Integer.valueOf(viaCnt) - 1;
									viaStr = "경유"+vi+"회";
								}else {
									viaStr = "직항";
								}

								// 직항, 경우 최종 체크 - 경우가 1개라도 있을경우 직항제외함.
								if(nTempViaCnt > nMaxViaCnt) {
									nMaxViaCnt = nTempViaCnt;
								}

								int itnrSeq = itnrLstNode.path("itnrSeq").asInt(0);
								if(farIndex > 0){
									sbViaInfo.append("\n\n" + viaStr);
								}else {
									if (itnrSeq > 1) {
										sbViaInfo.append(viaStr+ "\n");
									}else{
										sbViaInfo.append(viaStr);
									}
								}

								int oneFltTime = this.getMinVal(itnrLstNode.path("totTime").asText("0"));
								totFltTime += oneFltTime;	//총소요시간

								//######################## 비행정보 ##########################################
								int fsi = 1;
								int k=0;
								logger.debug("yt node.itnrLst.fltLst - for k=" + k++);
								int fltIndex = 0;
								for(JsonNode fltLstNode : itnrLstNode.path("fltLst")) {
									//------------------------------
									// Set Response(JSON) - fltLst
									//------------------------------
									String sFltSeq = StringUtil.nullConvert(fltLstNode.path("fltSeq").toString());	// ROW KEY - 비행편순번

									responseFltLstVo = new Gson().fromJson(fltLstNode.toString(), SchAirFareResponseFltLstVo.class);
									responseFltLstVo.setOrgJson(fltLstNode.toString());			// json원문
									responseFltLstVo.setParentRowKey(sParentRowKey2);			// 상위 Row 키
									responseFltLstVo.setRowKey(sParentRowKey2 +":"+ sFltSeq);	// 현재 Row 키

									ds_jsonFltLst.add(responseFltLstVo);

									String sParentRowKey3 = sParentRowKey2 + ":" + sFltSeq; 			// 상위 키 = 운임ID + 여정 순번 + 비행편순번
									//------------------------------
									// Set Response(JSON) - fltPrcLst
									//------------------------------
									for(JsonNode fltPrcLstNode : fltLstNode.path("fltPrcLst")) {
										responseFltPrcLstVo = new Gson().fromJson(fltPrcLstNode.toString(), SchAirFareResponseFltPrcLstVo.class);
										responseFltPrcLstVo.setOrgJson(fltPrcLstNode.toString());	// json원문
										responseFltPrcLstVo.setParentRowKey(sParentRowKey3);		// 상위 키

										ds_jsonFltPrcLst.add(responseFltPrcLstVo);
									}
									//------------------------------
									// Set Response(JSON) - freeBaggLst
									//------------------------------
									for(JsonNode freeBaggLstNode : fltLstNode.path("freeBaggLst")) {
										responseFreeBaggLstVo = new Gson().fromJson(freeBaggLstNode.toString(), SchAirFareResponseFreeBaggLstVo.class);
										responseFreeBaggLstVo.setOrgJson(freeBaggLstNode.toString());	// json원문
										responseFreeBaggLstVo.setParentRowKey(sParentRowKey3);			// 상위 키

										ds_jsonFreeBaggLst.add(responseFreeBaggLstVo);
									}
									//------------------------------
									// Set Response(JSON) - hidStopLst
									//------------------------------
									for(JsonNode hidStopLstNode : fltLstNode.path("hidStopLst")) {
										responseHidStopLstVoo = new Gson().fromJson(hidStopLstNode.toString(), SchAirFareResponseHidStopLstVo.class);
										responseHidStopLstVoo.setOrgJson(hidStopLstNode.toString());	// json원문
										responseHidStopLstVoo.setParentRowKey(sParentRowKey3);			// 상위 키

										ds_jsonhidStopLst.add(responseHidStopLstVoo);
									}
									//------------------------------

									logger.debug("yt node.itnrLst - for k=" + k++);
									String enterTxt = "";
									String viaEnterTxt = "";
									if(fsi > 1){
										enterTxt = "\n";
										viaEnterTxt = "\n";
									}else {
										if(itnrSeq > 1){
											enterTxt = "\n\n";
										}
									}

									JsonNode fltPrcNode = fltLstNode.path("fltPrcLst").get(0);	//비행프라이싱정보

									//총환승시간
									int oneStopTime = this.getMinVal(fltLstNode.path("stopTime").asText("0"));
									totStopTime += oneStopTime;

									String mktAlCode = StringUtil.nullConvert(fltLstNode.path("mktAlCd"  ).textValue());	//마케팅항공코드
									String mktAlName = StringUtil.nullConvert(fltLstNode.path("mktAlNm"  ).textValue());	//마케팅항공이름
									String oprAlCode = StringUtil.nullConvert(fltLstNode.path("oprAlCode").textValue());	//운항항공코드
									String oprAlName = StringUtil.nullConvert(fltLstNode.path("oprAlName").textValue());	//운항항공이름
									String mktFltNo  = StringUtil.nullConvert(fltLstNode.path("mktFltNo" ).textValue());
                                    String eqmtName = StringUtil.nullConvert(fltLstNode.path("eqmtName"  ).textValue());	//기종명
									String oprAlNmDp   = "";
									String oprAlCodeDp = "";

									sbMktAlCodes.append(mktAlCode + ",");
									sbOprAlCodes.append(oprAlCode + ",");
                                    sbEqmtNames.append(eqmtName + ",");

									String deptAptCode = StringUtil.nullConvert(fltLstNode.path("deptAptCode").textValue());
									String deptDate = fltLstNode.path("deptDate").asText("0");			//출발일자
									String deptTime = StringUtils.leftPad(fltLstNode.path("deptTime").asText("0"), 4, "0");			//출발시간
									String arrvAptCode = StringUtil.nullConvert(fltLstNode.path("arrvAptCode").textValue());	//도착항공코드
									String arrvDate = fltLstNode.path("arrvDate").asText("0");			//도착일자
									String arrvTime = StringUtils.leftPad(fltLstNode.path("arrvTime").asText("0"), 4, "0");			//도착시간
									cabinType = StringUtil.nullConvert(   fltLstNode.path("cabinType").textValue());	//좌석등급
									bookClass = StringUtil.nullConvert(   fltLstNode.path("bookClass").textValue());	//부킹클래스
									fareBasis = StringUtil.nullConvert(fltPrcNode.path("fareBasis").textValue());	//fareBasis
									tktDesg = StringUtil.nullConvert(fltPrcNode.path("tktDesg").textValue());	//tktDesg
									fareType  = StringUtil.nullConvert(fltPrcNode.path("fareType" ).textValue());	//운임유형
									pasnType  = StringUtil.nullConvert(fltPrcNode.path("pasnType" ).textValue());	//승객구분

									sbPasnTypes.append(pasnType + ",");

									String dpDeptTime = StringUtils.left(deptTime, 2) + ":" + StringUtils.right(deptTime, 2);
									String dpArrvTime = StringUtils.left(arrvTime, 2) + ":" + StringUtils.right(arrvTime, 2);

									sbMktFltNoInfo.append(mktAlCode + mktFltNo + ",");	//결과내 검색을 위한 항공편명 조합
									sbBookClassInfo.append(bookClass + ",");	//결과내 검색을 위한 부킹클래스 조합;
									sbFareBasisInfo.append(fareBasis + ",");	//결과내 검색을 위한 fareBasis 조합;

									//좌석결합여부
									if(!cabinMap.isEmpty() && !cabinMap.containsKey(cabinType)){
										cabinComplexChk = "Y";
									}
									cabinMap.put(cabinType, cabinType);

									//항공사결합여부
									if(!mktAlMap.isEmpty() && !mktAlMap.containsKey(mktAlCode)){
										alComplexChk = "Y";
									}
									mktAlMap.put(mktAlCode, mktAlCode);

									sbCabinTypes.append(cabinType + ",");
									sbFareTypes.append( fareType + ",");

									//좌석등급명
									String cabinTypeNm = "";
									if(seatGradList != null ){
										for(ComComDtlCQcVo scodeVo : seatGradList){
											if(scodeVo.getComDtlCd().equals(cabinType)){
												cabinTypeNm = scodeVo.getComDtlCdNm();
											}
										}
									}
									//운임유형명
									String fareTypeNm = "";
									if(fareTypeList != null ){
										for(ComComDtlCQcVo fcodeVo : fareTypeList){
											if(fcodeVo.getComDtlCd().equals(fareType)){
												fareTypeNm = fcodeVo.getComDtlCdNm();
											}
										}
									}

									if(farIndex==0){
										if(fltIndex==0){
											sbFltSchdInfo.append(deptAptCode + " " + DateUtil.formatDate(deptDate, "MM/dd") + " " +  dpDeptTime + " => " +	arrvAptCode + " " + DateUtil.formatDate(arrvDate, "MM/dd") + " " + dpArrvTime + "  " + cabinTypeNm + "  " + bookClass);
											sbAlInfo.append(      mktAlName + (eqmtName == null || eqmtName.isEmpty() ? "" : " (" + eqmtName + ")"));
                                            sbFltNoInfo.append(   mktAlCode+mktFltNo);
											sbFbInfo.append(      StringUtils.isEmpty(tktDesg) ? fareBasis : fareBasis + "/" + tktDesg);
											sbFareTypeInfo.append(fareTypeNm);
										}else{
											sbFltSchdInfo.append("\n"+deptAptCode + " " + DateUtil.formatDate(deptDate, "MM/dd") + " " +  dpDeptTime + " => " +	arrvAptCode + " " + DateUtil.formatDate(arrvDate, "MM/dd") + " " + dpArrvTime + "  " + cabinTypeNm + "  " + bookClass);
											sbAlInfo.append(      "\n"+mktAlName+(eqmtName == null || eqmtName.isEmpty() ? "" : " (" + eqmtName + ")"));
											sbFltNoInfo.append(   "\n"+mktAlCode+mktFltNo);
											sbFbInfo.append(      "\n"+(StringUtils.isEmpty(tktDesg) ? fareBasis : fareBasis + "/" + tktDesg));
											sbFareTypeInfo.append("\n"+fareTypeNm);
										}
									}else {
										if(fltIndex==0){
											sbFltSchdInfo.append("\n"+deptAptCode + " " + DateUtil.formatDate(deptDate, "MM/dd") + " " +  dpDeptTime + " => " +	arrvAptCode + " " + DateUtil.formatDate(arrvDate, "MM/dd") + " " + dpArrvTime + "  " + cabinTypeNm + "  " + bookClass);
											sbAlInfo.append(      "\n"+mktAlName + (eqmtName == null || eqmtName.isEmpty() ? "" : " (" + eqmtName + ")"));
											sbFltNoInfo.append(   "\n"+mktAlCode+mktFltNo);
											sbFbInfo.append(      "\n"+(StringUtils.isEmpty(tktDesg) ? fareBasis : fareBasis + "/" + tktDesg));
											sbFareTypeInfo.append("\n\n"+fareTypeNm);
										}else{
											sbFltSchdInfo.append("\n"+deptAptCode + " " + DateUtil.formatDate(deptDate, "MM/dd") + " " +  dpDeptTime + " => " +	arrvAptCode + " " + DateUtil.formatDate(arrvDate, "MM/dd") + " " + dpArrvTime + "  " + cabinTypeNm + "  " + bookClass);
											sbAlInfo.append(      "\n"+mktAlName + (eqmtName == null || eqmtName.isEmpty() ? "" : " (" + eqmtName + ")"));
											sbFltNoInfo.append(   "\n"+mktAlCode+mktFltNo);
											sbFbInfo.append(      "\n"+(StringUtils.isEmpty(tktDesg) ? fareBasis : fareBasis + "/" + tktDesg));
											sbFareTypeInfo.append("\n"+fareTypeNm);
										}
									}

									sbViaInfo.append(     viaEnterTxt);

									// 운항항공사코드가 존재하면서 해당Flight의 마케팅항공사와 다를 경우
									if(!StringUtil.isEmpty(oprAlCode) && !mktAlCode.equals(oprAlCode)){
										oprAlNmDp   = "\n<fc v='red'>" + oprAlName + "</fc>";
										oprAlCodeDp = "\n<fc v='red'>" + oprAlCode + "</fc>";
										sbViaInfo.append(    "\n");
										sbAlInfo.append(     oprAlNmDp);
										sbFltNoInfo.append(  oprAlCodeDp);
										sbFltSchdInfo.append("\n");
										sbFbInfo.append(     "\n");
										sbFareTypeInfo.append("\n");
									}

									fsi++;

									//################# SEG단위 필터설정 #########################################
									//개별탭-필터설정 : 마케팅항공사, 운항항공사, 좌석유형, 운임유형, 승객유형
									this.setFilterMap(fareFltrMap, newSplyCd, mktAlCode, mktAlName  , adtTamt, FltrType.MKT_AL_CODES);
									this.setFilterMap(fareFltrMap, newSplyCd, oprAlCode, oprAlName  , adtTamt, FltrType.OPR_AL_CODES);
									this.setFilterMap(fareFltrMap, newSplyCd, cabinType, cabinTypeNm, 0L     , FltrType.CABIN_TYPES);
									this.setFilterMap(fareFltrMap, newSplyCd, fareType , fareTypeNm , 0L     , FltrType.FARE_TYPES);
									this.setFilterMap(fareFltrMap, newSplyCd, pasnType , pasnType   , 0L     , FltrType.PASN_TYPES);

									//통합탭-필터설정 : 마케팅항공사, 운항항공사, 좌석유형, 운임유형, 승객유형
									this.setFilterMap(fareFltrMap, ALL_TAP, mktAlCode, mktAlName  , adtTamt, FltrType.MKT_AL_CODES);
									this.setFilterMap(fareFltrMap, ALL_TAP, oprAlCode, oprAlName  , adtTamt, FltrType.OPR_AL_CODES);
									this.setFilterMap(fareFltrMap, ALL_TAP, cabinType, cabinTypeNm, 0L     , FltrType.CABIN_TYPES);
									this.setFilterMap(fareFltrMap, ALL_TAP, fareType , fareTypeNm , 0L     , FltrType.FARE_TYPES);
									this.setFilterMap(fareFltrMap, ALL_TAP, pasnType , pasnType   , 0L     , FltrType.PASN_TYPES);

									int acctCodeSetSize = fltPrcNode.path("acctCodeSet").size();

									//if(fltPrcNode.path("acctCodeSet").textValue() != null){
									if(acctCodeSetSize > 0) {
										ArrayNode acctNode = (ArrayNode) fltPrcNode.path("acctCodeSet");
										for(JsonNode acctCodeNode : acctNode){
											String accntCode = acctCodeNode.asText();
											sbAcctCodes.append(accntCode + ",");

											//개별탭-필터설정 : accountCode
											this.setFilterMap(fareFltrMap, newSplyCd, accntCode, accntCode, 0L, FltrType.ACCT_CODES);

											//개별탭-필터설정 : accountCode
											this.setFilterMap(fareFltrMap, ALL_TAP, accntCode, accntCode, 0L, FltrType.ACCT_CODES);
										}
									}
									//################ SEG단위 필터설정 ###############################################
									fltIndex++;
								}	//비행정보
							}	//여정정보

							//-------------------------------------
							// 직항, 경유 필터 설정 - 운임별로 설정함.
							//-------------------------------------
							String viaStr2 = "";
							if(nMaxViaCnt > 1) {	//경유횟수:viaCnt=1은 직항을 의미
								int vi = nMaxViaCnt - 1;
								viaStr2 = "경유"+vi+"회";
							}else {
								viaStr2 = "직항";
							}

							this.setFilterMap(fareFltrMap, newSplyCd, String.valueOf(nMaxViaCnt), viaStr2, adtTamt, FltrType.VIA_CNT);	// 개별탭-필터설정 : 직항/경유
							this.setFilterMap(fareFltrMap, ALL_TAP  , String.valueOf(nMaxViaCnt), viaStr2, adtTamt, FltrType.VIA_CNT);	// 통합탭-필터설정 : 직항/경유
							//-------------------------------------
							// 노출제어 추가
							//-------------------------------------
							this.setFilterMap(fareFltrMap, newSplyCd, sIsueAirlRuleFarExlsTrgtYn, sIsueAirlRuleFarExlsTrgtYn, 0L     , FltrType.ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN);
							this.setFilterMap(fareFltrMap, newSplyCd, sMCTRuleFarExlsTrgtYn     , sMCTRuleFarExlsTrgtYn     , 0L     , FltrType.MCT_RULE_FAR_EXLS_TRGT_YN      );
							this.setFilterMap(fareFltrMap, ALL_TAP  , sIsueAirlRuleFarExlsTrgtYn, sIsueAirlRuleFarExlsTrgtYn, 0L     , FltrType.ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN);
							this.setFilterMap(fareFltrMap, ALL_TAP  , sMCTRuleFarExlsTrgtYn     , sMCTRuleFarExlsTrgtYn     , 0L     , FltrType.MCT_RULE_FAR_EXLS_TRGT_YN      );
							//-------------------------------------

							sbAlInfo.append(     "\n");	// 다음줄과 공백띄우기 위해 삽입
							sbFltNoInfo.append(  "\n");	// 다음줄과 공백띄우기 위해 삽입
							sbFltSchdInfo.append("\n");	// 다음줄과 공백띄우기 위해 삽입
							sbFbInfo.append(     "\n");	// 다음줄과 공백띄우기 위해 삽입

							String pftktSeq      =            StringUtil.nullConvert(farVoLst.path("pftktSeq"     ).textValue());	                                // PF운임룰번호
							String pfTktYn       = "Y".equals(StringUtil.nullConvert(farVoLst.path("pftktYn"      ).textValue())) ? "[PF] (No."+pftktSeq+") " : "";	// PF티켓여부
							imdtPayPsblYn =            StringUtil.nullConvert(farVoLst.path("imdtPayPsblYn").textValue());			                  		//즉시결제가능여부
							gnrlEventCds  = "";	//필터용
							String gnrlEventCdDp = "";	//화면표시용
							sbGnrlEventCdDp = new StringBuilder("");	//화면표시용

							String gnrlEventCd1  = StringUtil.nullConvert(farVoLst.path("gnrlEvent1Cd").textValue());	//일반이벤트코드1
							String gnrlEventCd2  = StringUtil.nullConvert(farVoLst.path("gnrlEvent2Cd").textValue());	//일반이벤트코드2
							String gnrlEvent1Nm  = StringUtil.nullConvert(farVoLst.path("gnrlEvent1Nm").textValue());	//일반이벤트_1_명
							String gnrlEvent2Nm  = StringUtil.nullConvert(farVoLst.path("gnrlEvent2Nm").textValue());	//일반이벤트_2_명
							String atmtIsueYn    = StringUtil.nullConvert(farVoLst.path("atmtIsueYn"  ).textValue());	//자동발권여부 - 20200311 : '자동발권여부' > 'GDS자동발권가능여부'로 변경
							String ntytFixFarDvCd=            StringUtil.nullConvert(farVoLst.path("ntytFixFarDvCd").textValue());					// 미확정운임구분코드
							fixFarYn      =  "".equals(StringUtil.nullConvert(farVoLst.path("ntytFixFarDvCd").textValue())) ? "Y" : "N";		// 확정운임여부

							String bestFarYn     =            StringUtil.nullConvert(farVoLst.path("bestFarYn"     ).textValue());					// best운임여부
							int    bestFarRn     =                                   farVoLst.path("bestFarRn"     ).asInt(0);						// 20191031 : best 관련 수정(bestFarRn 추가)
							if("Y".equals(bestFarYn)) {
								if(0 != bestFarRn) {
									bestFarYn = "[BEST] (Rank. " + bestFarRn + ") ";
								} else {
									bestFarYn = "[BEST] (Rank -) ";
								}
							} else {
								bestFarYn = "";
							}


							String eventId =            StringUtil.nullConvert(farVoLst.path("eventId").textValue());					//eventId
							gnrlEventCds = gnrlEventCd1+","+gnrlEventCd2;

							if(!StringUtil.isEmpty(gnrlEventCd1)) {
								sbGnrlEventCdDp.append("No."+eventId+" - "+gnrlEventCd1+" "+gnrlEvent1Nm);
							}
							if(!StringUtil.isEmpty(gnrlEventCd2)) {
								if(!StringUtil.isEmpty(sbGnrlEventCdDp.toString())) {
									sbGnrlEventCdDp.append(","+ gnrlEventCd2+" "+gnrlEvent2Nm);
								} else {
									sbGnrlEventCdDp.append("No."+eventId+" - "+gnrlEventCd2+" "+gnrlEvent2Nm);
								}
							}

							if(farIndex==0){
								if(StringUtil.isEmpty(sbGnrlEventCdDp.toString())) {
									gnrlEventCdDp = "(가는편)일반이벤트 - ";
								} else {
									gnrlEventCdDp = "(가는편)일반이벤트(" + sbGnrlEventCdDp.toString() + ")";
								}
							}else{
								if(StringUtil.isEmpty(sbGnrlEventCdDp.toString())) {
									gnrlEventCdDp = "\n(오는편)일반이벤트 - ";
								} else {
									gnrlEventCdDp = "\n(오는편)일반이벤트(" + sbGnrlEventCdDp.toString() + ")";
								}
							}

							//########## 발권항공사 setting #####################

							// yt : 발권항공사 쿼리 시행됨 - 성능개선 필요.

							tktAlCode = StringUtil.nullConvert(farVoLst.path("tktAlCode").textValue());	//발권항공사코드
							tktAlName = StringUtil.nullConvert(farVoLst.path("tktAlName").textValue());	//발권항공사명

							//개별탭-필터설정 : 발권항공사, 항공사결합, 좌석결합, 즉시결제가능여부, 운임확정여부, 일반이벤트코드, 할인이벤트코드, 금액, 총소요시간, 총환승시간
							this.setFilterMap(fareFltrMap, newSplyCd, tktAlCode      , tktAlName      , adtTamt, FltrType.TKT_AL_CODE);
							this.setFilterMap(fareFltrMap, newSplyCd, alComplexChk   , alComplexChk   , 0L     , FltrType.AL_COMPLEX);
							this.setFilterMap(fareFltrMap, newSplyCd, cabinComplexChk, cabinComplexChk, 0L     , FltrType.CABIN_COMPLEX);
							this.setFilterMap(fareFltrMap, newSplyCd, imdtPayPsblYn  , imdtPayPsblYn  , 0L     , FltrType.IMDT_PAY_PSBL_YN);
							this.setFilterMap(fareFltrMap, newSplyCd, fixFarYn       , fixFarYn       , 0L     , FltrType.FIX_FAR_YN);
							this.setFilterMap(fareFltrMap, newSplyCd, gnrlEventCd1   , gnrlEvent1Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
							this.setFilterMap(fareFltrMap, newSplyCd, gnrlEventCd2   , gnrlEvent2Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
							this.setFilterMap(fareFltrMap, newSplyCd, dcEventCd      , dcEventNm      , 0L     , FltrType.DC_EVENT_CODE);
							this.setFilterMap(fareFltrMap, newSplyCd, FltrType.ADT_T_AMT.getFltrFld(), FltrType.ADT_T_AMT.getFltrFld(), adtTamt, FltrType.ADT_T_AMT);
							this.setTimeFilterMap(fareFltrMap, newSplyCd, totFltTime, FltrType.TOT_TIME);
							this.setTimeFilterMap(fareFltrMap, newSplyCd, totStopTime, FltrType.STOP_TIME);

							//통합탭-필터설정 : 발권항공사, 항공사결합, 좌석결합, 즉시결제가능여부, 운임확정여부, 일반이벤트코드, 할인이벤트코드, 금액, 총소요시간, 총환승시간
							this.setFilterMap(fareFltrMap, ALL_TAP, tktAlCode      , tktAlName      , adtTamt, FltrType.TKT_AL_CODE);
							this.setFilterMap(fareFltrMap, ALL_TAP, alComplexChk   , alComplexChk   , 0L     , FltrType.AL_COMPLEX);
							this.setFilterMap(fareFltrMap, ALL_TAP, cabinComplexChk, cabinComplexChk, 0L     , FltrType.CABIN_COMPLEX);
							this.setFilterMap(fareFltrMap, ALL_TAP, imdtPayPsblYn  , imdtPayPsblYn  , 0L     , FltrType.IMDT_PAY_PSBL_YN);
							this.setFilterMap(fareFltrMap, ALL_TAP, fixFarYn       , fixFarYn       , 0L     , FltrType.FIX_FAR_YN);
							this.setFilterMap(fareFltrMap, ALL_TAP, gnrlEventCd1   , gnrlEvent1Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
							this.setFilterMap(fareFltrMap, ALL_TAP, gnrlEventCd2   , gnrlEvent2Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
							this.setFilterMap(fareFltrMap, ALL_TAP, dcEventCd      , dcEventNm      , 0L     , FltrType.DC_EVENT_CODE);
							this.setFilterMap(fareFltrMap, ALL_TAP, FltrType.ADT_T_AMT.getFltrFld(), FltrType.ADT_T_AMT.getFltrFld(), adtTamt, FltrType.ADT_T_AMT);
							this.setTimeFilterMap(fareFltrMap, ALL_TAP, totFltTime , FltrType.TOT_TIME);
							this.setTimeFilterMap(fareFltrMap, ALL_TAP, totStopTime, FltrType.STOP_TIME);


							//발권항공사룰운임제외대상여부/MCT룰운임제외대상여부
							String isueAlirlRuleFarExlsTrgtYn = "Y".equals(StringUtil.nullConvert(farVoLst.path("isueAlirlRuleFarExlsTrgtYn").textValue())) ? "발권항공사룰운임제외대상 " : "";
							String mctRuleFarExlsTrgtYn       = "Y".equals(StringUtil.nullConvert(farVoLst.path("mctRuleFarExlsTrgtYn"      ).textValue())) ? "MCT룰운임제외대상 " : "";
							String exlsTargnYnStr = isueAlirlRuleFarExlsTrgtYn+mctRuleFarExlsTrgtYn;

							if(!"".equals(exlsTargnYnStr)) {
								exlsTargnYnStr = exlsTargnYnStr + "\n";
							}

							//특별적립마일리지 텍스트 - 편도결합/비결합 공통 로직은 buildSpclColtMlgTexts()로 추출
							SpclColtMlgTexts spclColtMlgTexts = this.buildSpclColtMlgTexts(farVoLst);
							sbGnrlAdtSpclColtMlgAmt = spclColtMlgTexts.sbGnrlAdtSpclColtMlgAmt;
							sbGnrlChdSpclColtMlgAmt = spclColtMlgTexts.sbGnrlChdSpclColtMlgAmt;
							sbDtcmAdtSpclColtMlgAmt = spclColtMlgTexts.sbDtcmAdtSpclColtMlgAmt;
							sbDtcmChdSpclColtMlgAmt = spclColtMlgTexts.sbDtcmChdSpclColtMlgAmt;




							sbAdtGnrlDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "일반 적용가 ADT " + sbAdtGnrlDcAmtInfoTemp.toString());
							sbChdGnrlDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "일반 적용가 CHD " + sbChdGnrlDcAmtInfoTemp.toString());
							sbInfGnrlDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "일반 적용가 INF " + sbInfGnrlDcAmtInfoTemp.toString());
							sbAdtDtcmDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "닷컴 적용가 ADT " + sbAdtDtcmDcAmtInfoTemp.toString());
							sbChdDtcmDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "닷컴 적용가 CHD " + sbChdDtcmDcAmtInfoTemp.toString());
							sbInfDtcmDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "닷컴 적용가 INF " + sbInfDtcmDcAmtInfoTemp.toString());

							//카드프로모션정보 - 편도결합/비결합 공통 로직은 buildCardPromotionTexts()로 추출
							CardPromotionTexts cardPromotionTexts = this.buildCardPromotionTexts(farVoLst, feeAmountTexts, pasnType, newSplyCd, fareFltrMap, sbGnrlCardPromId, sbGnrlCardPromEventCd, sbGnrlCardNm, sbGnrlCardDcInfo, sbGnrlCardDcAplAmt, sbGnrlCardDcTotalAmt, sbDtcmCardPromId, sbDtcmCardPromEventCd, sbDtcmCardNm, sbDtcmCardDcInfo, sbDtcmCardDcAplAmt, sbDtcmCardDcTotalAmt, sbCardPromIds);
							sbCardGnrlAdt = cardPromotionTexts.sbCardGnrlAdt;
							sbCardGnrlChd = cardPromotionTexts.sbCardGnrlChd;
							sbCardGnrlInf = cardPromotionTexts.sbCardGnrlInf;
							sbCardDtcmAdt = cardPromotionTexts.sbCardDtcmAdt;
							sbCardDtcmChd = cardPromotionTexts.sbCardDtcmChd;
							sbCardDtcmInf = cardPromotionTexts.sbCardDtcmInf;
							isExistCardGnrlInfo = cardPromotionTexts.isExistCardGnrlInfo;
							isExistCardDtcmInfo = cardPromotionTexts.isExistCardDtcmInfo;


							//발권수수료(TASF) 텍스트 - 편도결합/비결합 공통 로직은 buildTasfFeeTexts()로 추출
							TasfFeeTexts tasfFeeTexts = this.buildTasfFeeTexts(farVoLst);
							sbIsueFeeGnrlAdt = tasfFeeTexts.sbIsueFeeGnrlAdt;
							sbIsueFeeGnrlChd = tasfFeeTexts.sbIsueFeeGnrlChd;
							sbIsueFeeGnrlInf = tasfFeeTexts.sbIsueFeeGnrlInf;
							sbIsueFeeDtcmAdt = tasfFeeTexts.sbIsueFeeDtcmAdt;
							sbIsueFeeDtcmChd = tasfFeeTexts.sbIsueFeeDtcmChd;
							sbIsueFeeDtcmInf = tasfFeeTexts.sbIsueFeeDtcmInf;


							//------------------------------------
							// 공급코드, 발권항공사, 여정타입..
							//------------------------------------
							String sSplyCdFm          = farVoLst.path("splyCd"    ).textValue();
							String sCurrCode          = StringUtil.nullConvert(farVoLst.path("currCode"      ).asText());
							String sAdtBscAmtFm       = sBasicPasnTypeNmAdt +NumberUtil.formatNumber(String.valueOf(farVoLst.path("adtBscAmt"      ).asLong(0)), "#,###,###") + " " + sCurrCode;
							String sChdBscAmtFm       = sBasicPasnTypeNmChd +NumberUtil.formatNumber(String.valueOf(farVoLst.path("chdBscAmt"      ).asLong(0)), "#,###,###") + " " + sCurrCode;
							String sInfBscAmtFm       = sBasicPasnTypeNmInf +NumberUtil.formatNumber(String.valueOf(farVoLst.path("infBscAmt"      ).asLong(0)), "#,###,###") + " " + sCurrCode;
							String sAdtQchrgAmtFm     =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("adtQchrgAmt"    ).asLong(0)), "#,###,###");
							String sChdQchrgAmtFm     =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("chdQchrgAmt"    ).asLong(0)), "#,###,###");
							String sInfQchrgAmtFm     =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("infQchrgAmt"    ).asLong(0)), "#,###,###");
							String sAdtFuelExchgAmtFm =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("adtFuelExchgAmt").asLong(0)), "#,###,###");
							String sChdFuelExchgAmtFm =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("chdFuelExchgAmt").asLong(0)), "#,###,###");
							String sInfFuelExchgAmtFm =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("infFuelExchgAmt").asLong(0)), "#,###,###");
							String sAdtTaxAmt         =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("adtTaxAmt"      ).asLong(0)), "#,###,###");
							String sChdTaxAmt         =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("chdTaxAmt"      ).asLong(0)), "#,###,###");
							String sInfTaxAmt         =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("infTaxAmt"      ).asLong(0)), "#,###,###");
							String sAdtTamtFm         =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("adtTamt"        ).asLong(0)), "#,###,###");
							String sChdTamtFm         =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("chdTamt"        ).asLong(0)), "#,###,###");
							String sInfTamtFm         =         NumberUtil.formatNumber(String.valueOf(farVoLst.path("infTamt"        ).asLong(0)), "#,###,###");

							sbSplyInfo = new StringBuilder("");
							if(farIndex==0){
								sbSplyInfo.append("(가는편)공급코드 "      + sSplyCdFm);
							}else{
								sbSplyInfo.append("\n\n(오는편)공급코드 "      + sSplyCdFm);
							}



							// 발권항공사 : bizCom.isDebug == true, isueAirlRuleFarExlsTrgtYn == "Y" 일 경우 '(노출제어)' 붙임
							if(bIsDebug && "Y".equals(sIsueAirlRuleFarExlsTrgtYn)) {
								sbSplyInfo.append(" 발권항공사 "   + tktAlCode + " <fc v='red'>(노출제어)</fc>");
							} else {
								sbSplyInfo.append(" 발권항공사 "   + tktAlCode);
							}

							// 미노출 Text 표시
							if(StringUtils.isNotEmpty(sFarExlsResnCd)){
								String resultText = sFarExlsResnText;

								if(StringUtils.isNotEmpty(resultText)){
									sbSplyInfo.append(" <fc v='#D2006E'>("+resultText+")</fc>");
								}
							}

							// 룰셋여정타입 : bizCom.isDebug == true, MCTRuleFarExlsTrgtYn == "Y" 일 경우 '(MCT노출제어)' 붙임
							if(bIsDebug && "Y".equals(sMCTRuleFarExlsTrgtYn)) {
								sbSplyInfo.append(" 룰셋여정타입 " + gdsItnrTypeCd + " <fc v='red'>(MCT노출제어)</fc>");
							} else {
								sbSplyInfo.append(" 룰셋여정타입 " + gdsItnrTypeCd);
							}

							String sBscAmtFmAdt = "\n기본운임 "   + sAdtBscAmtFm + " (Q "    + sAdtQchrgAmtFm + " FUEL " + sAdtFuelExchgAmtFm + " TAX "  + sAdtTaxAmt + ")" + " Total " + sAdtTamtFm;
							String sBscAmtFmChd = "\n기본운임 "   + sChdBscAmtFm + " (Q "    + sChdQchrgAmtFm + " FUEL " + sChdFuelExchgAmtFm + " TAX "  + sChdTaxAmt + ")" + " Total " + sChdTamtFm;
							String sBscAmtFmInf = "\n기본운임 "   + sInfBscAmtFm + " (Q "    + sInfQchrgAmtFm + " FUEL " + sInfFuelExchgAmtFm + " TAX "  + sInfTaxAmt + ")" + " Total " + sInfTamtFm;
							//------------------------------------
							// 대리점 커미션정보
							//------------------------------------
							sbAgtCmsnGnrlTitle = new StringBuilder("");
							sbAgtCmsnDtcmTitle = new StringBuilder("");
							sbAgtCmsnGnrlAdt   = new StringBuilder("");
							sbAgtCmsnGnrlChd   = new StringBuilder("");
							sbAgtCmsnGnrlInf   = new StringBuilder("");
							sbAgtCmsnDtcmAdt   = new StringBuilder("");
							sbAgtCmsnDtcmChd   = new StringBuilder("");
							sbAgtCmsnDtcmInf   = new StringBuilder("");

							String agtCmsnSeq = StringUtil.nullConvert(farVoLst.path("agtCmsnSeq").asText());

							if(StringUtil.isEmpty(agtCmsnSeq)) {
								sbAgtCmsnGnrlTitle.append("\n일반 대리점컴 ");
								sbAgtCmsnGnrlAdt.append(  " ADT - ");
								sbAgtCmsnGnrlChd.append(  " CHD - ");
								sbAgtCmsnGnrlInf.append(  " INF - ");
								sbAgtCmsnDtcmTitle.append("\n닷컴 대리점컴 ");
								sbAgtCmsnDtcmAdt.append(  " ADT - ");
								sbAgtCmsnDtcmChd.append(  " CHD - ");
								sbAgtCmsnDtcmInf.append(  " INF - ");
							}else {
								String agtCmsnAdtCmsnTrf = StringUtil.nullConvert(farVoLst.path("agtCmsnAdtCmsnTrf").asText());	// 대리점커미션성인커미션오율
								String agtCmsnChdCmsnTrf = StringUtil.nullConvert(farVoLst.path("agtCmsnChdCmsnTrf").asText());	// 대리점커미션아동커미션오율
								String agtCmsnInfCmsnTrf = StringUtil.nullConvert(farVoLst.path("agtCmsnInfCmsnTrf").asText());	// 대리점커미션유아커미션오율
								String agtCmsnGnrlAdtAmt = StringUtil.nullConvert(farVoLst.path("agtCmsnGnrlAdtAmt").asText());	// 대리점커미션일반성인금액
								String agtCmsnGnrlChdAmt = StringUtil.nullConvert(farVoLst.path("agtCmsnGnrlChdAmt").asText());	// 대리점커미션일반아동금액
								String agtCmsnGnrlInfAmt = StringUtil.nullConvert(farVoLst.path("agtCmsnGnrlInfAmt").asText());	// 대리점커미션일반유아금액
								String agtCmsnDtcmAdtAmt = StringUtil.nullConvert(farVoLst.path("agtCmsnDtcmAdtAmt").asText());	// 대리점커미션닷컴성인금액
								String agtCmsnDtcmChdAmt = StringUtil.nullConvert(farVoLst.path("agtCmsnDtcmChdAmt").asText());	// 대리점커미션닷컴아동금액
								String agtCmsnDtcmInfAmt = StringUtil.nullConvert(farVoLst.path("agtCmsnDtcmInfAmt").asText());	// 대리점커미션닷컴유아금액

								// 대리점커미션적용방식 : R - 정률, A - 정액
								String agtCmsnAplMthdCd = StringUtil.nullConvert(farVoLst.path("agtCmsnAplMthdCd"   ).asText());
								if("R".equals(agtCmsnAplMthdCd)) {
									//------------------------
									// 정률
									//------------------------
									sbAgtCmsnGnrlTitle.append("\n일반 대리점컴 (No."+agtCmsnSeq+")");
									sbAgtCmsnGnrlAdt.append( " ADT R " + agtCmsnAdtCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnGnrlAdtAmt, "#,###,###"));
									sbAgtCmsnGnrlChd.append(   " CHD R " + agtCmsnChdCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnGnrlChdAmt, "#,###,###"));
									sbAgtCmsnGnrlInf.append(   " INF R " + agtCmsnInfCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnGnrlInfAmt, "#,###,###"));
									sbAgtCmsnDtcmTitle.append("\n닷컴 대리점컴 (No."+agtCmsnSeq+")");
									sbAgtCmsnDtcmAdt.append(   " ADT R " + agtCmsnAdtCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnDtcmAdtAmt, "#,###,###"));
									sbAgtCmsnDtcmChd.append(   " CHD R " + agtCmsnChdCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnDtcmChdAmt, "#,###,###"));
									sbAgtCmsnDtcmInf.append(   " INF R " + agtCmsnInfCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnDtcmInfAmt, "#,###,###"));
								} else if("A".equals(agtCmsnAplMthdCd)) {
									//------------------------
									// 정액
									//------------------------
									sbAgtCmsnGnrlTitle.append("\n일반 대리점컴 (No."+agtCmsnSeq+")");
									sbAgtCmsnGnrlAdt.append(   " ADT A / " + NumberUtil.formatNumber(agtCmsnGnrlAdtAmt, "#,###,###"));
									sbAgtCmsnGnrlChd.append(   " CHD A / " + NumberUtil.formatNumber(agtCmsnGnrlChdAmt, "#,###,###"));
									sbAgtCmsnGnrlInf.append(   " INF A / " + NumberUtil.formatNumber(agtCmsnGnrlInfAmt, "#,###,###"));
									sbAgtCmsnDtcmTitle.append("\n닷컴 대리점컴 (No."+agtCmsnSeq+")");
									sbAgtCmsnDtcmAdt.append(   " ADT A / " + NumberUtil.formatNumber(agtCmsnDtcmAdtAmt, "#,###,###"));
									sbAgtCmsnDtcmChd.append(   " CHD A / " + NumberUtil.formatNumber(agtCmsnDtcmChdAmt, "#,###,###"));
									sbAgtCmsnDtcmInf.append(   " INF A / " + NumberUtil.formatNumber(agtCmsnDtcmInfAmt, "#,###,###"));
								}
							}

							//--------------------------------------
							// 발권수수료-성인원화전체금액
							//--------------------------------------
							JsonNode feeDtlNode = farVoLst.path("feeDtl");
							String gnrlAdtKrwAllAmt = !"".equals(feeDtlNode.path("gnrlAdtKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("gnrlAdtKrwAllAmt").asText(), "#,###,###") : "";
							String gnrlChdKrwAllAmt = !"".equals(feeDtlNode.path("gnrlChdKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("gnrlChdKrwAllAmt").asText(), "#,###,###") : "";
							String gnrlInfKrwAllAmt = !"".equals(feeDtlNode.path("gnrlInfKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("gnrlInfKrwAllAmt").asText(), "#,###,###") : "";
							String dtcmAdtKrwAllAmt = !"".equals(feeDtlNode.path("dtcmAdtKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("dtcmAdtKrwAllAmt").asText(), "#,###,###") : "";
							String dtcmChdKrwAllAmt = !"".equals(feeDtlNode.path("dtcmChdKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("dtcmChdKrwAllAmt").asText(), "#,###,###") : "";
							String dtcmInfKrwAllAmt = !"".equals(feeDtlNode.path("dtcmInfKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("dtcmInfKrwAllAmt").asText(), "#,###,###") : "";

							sbIsueFeeGnrlAdt.append(gnrlAdtKrwAllAmt);
							sbIsueFeeGnrlChd.append(gnrlChdKrwAllAmt);
							sbIsueFeeGnrlInf.append(gnrlInfKrwAllAmt);
							sbIsueFeeDtcmAdt.append(dtcmAdtKrwAllAmt);
							sbIsueFeeDtcmChd.append(dtcmChdKrwAllAmt);
							sbIsueFeeDtcmInf.append(dtcmInfKrwAllAmt);
							//--------------------------------------

							//###### 운임기본정보setting #####
							sbTmpFareBscInfo = new StringBuilder("");
							sbEtcInfo = new StringBuilder("");

							//운임확정정보
							if("Y".equals(fixFarYn )){
								sbTmpFareBscInfo.append("운임확정 ");
								sbEtcInfo.append(      " 운임확정");
							}else {
								sbTmpFareBscInfo.append("운임미확정 ");
								sbEtcInfo.append(       " 운임미확정 ("+ntytFixFarDvCd+")");
							}
							sbEtcInfo.append(" GDS자동발권가능여부" + atmtIsueYn);	// 20200311 : '자동발권여부' > 'GDS자동발권가능여부'로 변경


							//즉시결제가능여부
							if("Y".equals(imdtPayPsblYn)){
								sbTmpFareBscInfo.append("즉시결제 ");
							}else {
								sbTmpFareBscInfo.append("추후결제 ");
							}

							if("Y".equals(imdtPayPsblYn)){
								sbEtcInfo.append(" 즉시결제Y");
							}else {
								sbEtcInfo.append(" 즉시결제N");
							}

							//결제타입
							String payTypeCd   = StringUtil.nullConvert(farVoLst.path("payTypeCd"  ).textValue());	//결제유형코드
							String payTypeCdNm = StringUtil.nullConvert(farVoLst.path("payTypeCdNm").textValue());	//결제유형코드명
							sbTmpFareBscInfo.append("결제타입 "+payTypeCd+"["+payTypeCdNm+"]"+" ");

							sbEtcInfo.append(" 결제타입 ("+payTypeCd+")"+payTypeCdNm);

							String maxTktDate   = StringUtil.nullConvert(farVoLst.path("maxTktDate"  ).textValue());	//발권마감일자
							String maxTktTime   = StringUtil.nullConvert(farVoLst.path("maxTktTime"  ).textValue());	//발권마감시간

							if (StringUtils.isNotEmpty(maxTktDate)) {
								maxTktDate = DateUtil.formatDate(maxTktDate, "yyyy-MM-dd");
							}

							sbEtcInfo.append(" 발권마감일 " + maxTktDate + " " + maxTktTime);

							String evdnDocNcstYn   = StringUtil.nullConvert(farVoLst.path("evdnDocNcstYn"  ).textValue());	//증빙문서필요여부
							sbEtcInfo.append(" 룰셋증빙서류필요여부" + evdnDocNcstYn);	// 20191025 : 룰셋증빙서류필요여부

							if("N".equals(apiSupCode) || "L".equals(apiSupCode) || "R".equals(apiSupCode) || "P".equals(apiSupCode)){//NDC,루프드한자 일때만 Upsell 여부 추가
								String sUpsellTF = farVoLst.path("upsellPsblYn").textValue();
								sbEtcInfo.append("\nUPSELL 공급여부" + sUpsellTF);	// 20240319 : upsell 공급여부
							}

							if("G".equals(apiSupCode) || "M".equals(apiSupCode)){//갈릴레오,아마데우스 일때만 페어패밀리 여부 추가
								String sFareFmlYn = farVoLst.path("fareFmlYn").textValue();
								if("Y".equals(sFareFmlYn)){
									sbEtcInfo.append("\n페어패밀리 여부" + sFareFmlYn);	// 20240703 : 페어패밀리 여부
								}
							}

							fareBscInfo = "";
							sbFareBscInfo = new StringBuilder("");
							sbFareBscInfo.append("".equals(sbIsueFeeGnrlAdt.toString()) ? "" : sbIsueFeeGnrlAdt.toString() + "\n");
							sbFareBscInfo.append("".equals(sbIsueFeeDtcmAdt.toString()) ? sbTmpFareBscInfo.toString() : sbIsueFeeDtcmAdt.toString() + "\n" + sbTmpFareBscInfo.toString());

							//더보기 텍스트
							moreTxt = "";
							if(!StringUtil.isEmpty(sbAdtDtcmDcAmtInfo.toString()) || "Y".equals(isExistCardGnrlInfo) || "Y".equals(isExistCardDtcmInfo)){
								moreTxt = "+더보기";

								fareBscInfo = sbAdtDtcmDcAmtInfo.toString() + "\n" + sbFareBscInfo.toString();
							} else {
								fareBscInfo = sbFareBscInfo.toString();
							}

							//------------------------------------
							// 기본보기 - 공급코드, 발권항공사, 여정타입..  + 기타정보(운임확정, 자동발권여부, 즉시결제..)
							//------------------------------------
							sbDefaultInfoFm.append(sbSplyInfo.toString() + sbEtcInfo.toString());
							//------------------------------------
							// 기본보기 - 기본운임(Adt + Chd + Inf)
							//------------------------------------
							if(bCntAdt) sbDefaultInfoFm.append(sBscAmtFmAdt);
							if(bCntChd) sbDefaultInfoFm.append(sBscAmtFmChd);
							if(bCntInf) sbDefaultInfoFm.append(sBscAmtFmInf);
							//------------------------------------
							// 기본보기 - 판매룰 일반(Adt + Chd + Inf)
							//------------------------------------
							if(bCntAdt) sbDefaultInfoFm.append(sbSiteRuleGnrlAdt.toString());
							if(bCntChd) sbDefaultInfoFm.append(sbSiteRuleGnrlChd.toString());
							if(bCntInf) sbDefaultInfoFm.append(sbSiteRuleGnrlInf.toString());
							//------------------------------------
							// 기본보기 - PF, BEST..일반 (Adt + Chd + Inf)
							//------------------------------------
							if(bCntAdt) sbDefaultInfoFm.append(sbAdtGnrlDcAmtInfo.toString());
							if(bCntChd) sbDefaultInfoFm.append(sbChdGnrlDcAmtInfo.toString());
							if(bCntInf) sbDefaultInfoFm.append(sbInfGnrlDcAmtInfo.toString());
							//------------------------------------
							// 기본보기 - TASF 일반
							//------------------------------------
							if(bCntAdt) sbDefaultInfoFm.append(sbIsueFeeGnrlAdt.toString());
							if(bCntChd) sbDefaultInfoFm.append(sbIsueFeeGnrlChd.toString());
							if(bCntInf) sbDefaultInfoFm.append(sbIsueFeeGnrlInf.toString());
							//------------------------------------

							//------------------------------------
							// 더보기 - 일반이벤트(이벤트1, 이벤트2)
							//------------------------------------
							sbMoreViewInfoFm.append(gnrlEventCdDp);
							//------------------------------------
							// 더보기 - 특별적립마일리지 일반(Adt + Chd)
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbGnrlAdtSpclColtMlgAmt.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbGnrlChdSpclColtMlgAmt.toString());
							//------------------------------------
							// 더보기 - 카드 일반
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbCardGnrlAdt.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbCardGnrlChd.toString());
//					if(bCntInf) sbMoreViewInfoFm.append(sbCardGnrlInf.toString());		// 유아 - 화면에서 제외로 보류
							//------------------------------------
							// 더보기 - 판매룰 닷컴(Adt + Chd + Inf)
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbSiteRuleDtcmAdt.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbSiteRuleDtcmChd.toString());
							if(bCntInf) sbMoreViewInfoFm.append(sbSiteRuleDtcmInf.toString());
							//------------------------------------
							// 더보기 - PF, BEST..닷컴 (Adt + Chd + Inf)
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbAdtDtcmDcAmtInfo.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbChdDtcmDcAmtInfo.toString());
							if(bCntInf) sbMoreViewInfoFm.append(sbInfDtcmDcAmtInfo.toString());
							//------------------------------------
							// 더보기 - TASF 닷컴
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbIsueFeeDtcmAdt.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbIsueFeeDtcmChd.toString());
							if(bCntInf) sbMoreViewInfoFm.append(sbIsueFeeDtcmInf.toString());
							//------------------------------------
							// 더보기 - 특별적립마일리지 닷컴(Adt + Chd)
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbDtcmAdtSpclColtMlgAmt.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbDtcmChdSpclColtMlgAmt.toString());
							//------------------------------------
							// 더보기 - 카드 닷컴
							//------------------------------------
							if(bCntAdt) sbMoreViewInfoFm.append(sbCardDtcmAdt.toString());
							if(bCntChd) sbMoreViewInfoFm.append(sbCardDtcmChd.toString());
							//------------------------------------
							// 더보기 - 대리점컴 - 일반, 닷컴만 row로 출력함(Adt, Chd, Inf는 한줄로 표현함)
							//------------------------------------
							sbTempAgtCmsnGnrlStr = new StringBuilder("");
							sbTempAgtCmsnDtcmStr = new StringBuilder("");

							if(bCntAdt) sbTempAgtCmsnGnrlStr.append(sbAgtCmsnGnrlAdt.toString());	// 대리점컴 - 일반 - ADT
							if(bCntAdt) sbTempAgtCmsnDtcmStr.append(sbAgtCmsnDtcmAdt.toString());	// 대리점컴 - 닷컴 - ADT
							if(bCntChd) {
								// 대리점컴 - 일반 - CHD
								if(StringUtil.isEmpty(sbTempAgtCmsnGnrlStr.toString())) {
									sbTempAgtCmsnGnrlStr.append(sbAgtCmsnGnrlChd.toString());
								} else {
									sbTempAgtCmsnGnrlStr.append("," +sbAgtCmsnGnrlChd.toString());
								}
								// 대리점컴 - 닷컴 - CHD
								if(StringUtil.isEmpty(sbTempAgtCmsnDtcmStr.toString())) {
									sbTempAgtCmsnDtcmStr.append(sbAgtCmsnDtcmChd.toString());
								} else {
									sbTempAgtCmsnDtcmStr.append("," +sbAgtCmsnDtcmChd.toString());
								}
							}
							if(bCntInf) {
								// 대리점컴 - 일반 - INF
								if(StringUtil.isEmpty(sbTempAgtCmsnGnrlStr.toString())) {
									sbTempAgtCmsnGnrlStr.append(sbAgtCmsnGnrlInf.toString());
								} else {
									sbTempAgtCmsnGnrlStr.append("," +sbAgtCmsnGnrlInf.toString());
								}
								// 대리점컴 - 닷컴 - INF
								if(StringUtil.isEmpty(sbTempAgtCmsnDtcmStr.toString())) {
									sbTempAgtCmsnDtcmStr.append(sbAgtCmsnDtcmInf.toString());
								} else {
									sbTempAgtCmsnDtcmStr.append("," +sbAgtCmsnDtcmInf.toString());
								}
							}

							// 대리점컴 - 닷컴..(ADT, CHD, INF 로 표현함)
							sbMoreViewInfoFm.append(sbAgtCmsnGnrlTitle.toString() + sbTempAgtCmsnGnrlStr.toString());
							sbMoreViewInfoFm.append(sbAgtCmsnDtcmTitle.toString() + sbTempAgtCmsnDtcmStr.toString());
							//------------------------------------
							sbMoreViewInfoFm.append("\n");

							farIndex++;

						}	//인벤토리 타입 제외
						this.setCombFilterMap(fareFltrMap, ALL_TAP, sAirFarCombYn , "결합" , 0L, FltrType.AIR_FAR_COMB_YN);

					} else {
						//------------------------------
						// 실시간 운임 프라이싱 - 성인, 아동, 유아 count하여 화면 출력시 출력여부 참조
						//------------------------------
						int nCntAgeAdt  = 0;
						int nCntAgeChd  = 0;
						int nCntAgeInf  = 0;
						boolean bCntAdt = false;
						boolean bCntChd = false;
						boolean bCntInf = false;

						String sBasicPasnTypeNmAdt = "ADT ";
						String sBasicPasnTypeNmChd = "CHD ";
						String sBasicPasnTypeNmInf = "INF ";

						for(JsonNode farPrcLstNode : farLstNode.path("farPrcLst")) {
							//------------------------------
							// Set Response(JSON) - farPrcLst
							//------------------------------
							responseFarPrcLstVo = new Gson().fromJson(farPrcLstNode.toString(), SchAirFareResponseFarPrcLstVo.class);
							responseFarPrcLstVo.setOrgJson(farPrcLstNode.toString());	// json원문
							responseFarPrcLstVo.setParentRowKey(sParentRowKey1);		// 상위 키

							ds_jsonFarPrcLst.add(responseFarPrcLstVo);
							//------------------------------

							String sPasnType = StringUtil.nullConvert(farPrcLstNode.path("pasnType").asText());
							String sAgeType  = StringUtil.nullConvert(farPrcLstNode.path("ageType" ).asText());

							// ageType 기준으로 ADT/CHD/INF 표기
							if("ADT".equals(sAgeType)) {
								nCntAgeAdt++;
								// ageType과 pasnType이 다를 경우 pasnType을 괄호로 명기
								if(!sPasnType.equals(sAgeType)) sBasicPasnTypeNmAdt = sAgeType + " <fc v='red'>(PTC:"+sPasnType+")</fc> ";
							} else if("CHD".equals(sAgeType)) {
								nCntAgeChd++;
								// ageType과 pasnType이 다를 경우 pasnType을 괄호로 명기
								if(!sPasnType.equals(sAgeType)) sBasicPasnTypeNmChd = sAgeType + " <fc v='red'>(PTC:"+sPasnType+")</fc> ";
							} else if("INF".equals(sAgeType)) {
								nCntAgeInf++;
								// ageType과 pasnType이 다를 경우 pasnType을 괄호로 명기
								if(!sPasnType.equals(sAgeType)) sBasicPasnTypeNmInf = sAgeType + " <fc v='red'>(PTC:"+sPasnType+")</fc> ";
							}
						}
						if(nCntAgeAdt > 0) bCntAdt = true;
						if(nCntAgeChd > 0) bCntChd = true;
						if(nCntAgeInf > 0) bCntInf = true;
						//------------------------------

						//통합탭-필터설정 : 공급코드
						this.setFilterMap(fareFltrMap, ALL_TAP, newSplyCd, newSplyCd, 0L, FltrType.SPLY_CD);

						this.setCombFilterMap(fareFltrMap, ALL_TAP, sAirFarCombYn , "미결합" , 0L, FltrType.AIR_FAR_COMB_YN);
						this.setCombFilterMap(fareFltrMap, newSplyCd, sAirFarCombYn , "미결합" , 0L, FltrType.AIR_FAR_COMB_YN);

						sbMktAlCodes = new StringBuilder("");
						sbOprAlCodes = new StringBuilder("");
						sbPasnTypes  = new StringBuilder("");
						sbCabinTypes = new StringBuilder("");
						sbFareTypes  = new StringBuilder("");
						sbAcctCodes  = new StringBuilder("");

						//운임 계산용 금액/포맷문자열 - 편도결합/비결합 공통 로직은 computeFeeAmountTexts()로 추출
						FeeAmountTexts feeAmountTexts = this.computeFeeAmountTexts(farLstNode);
						long gnrlAdtEtcAmtL = feeAmountTexts.gnrlAdtEtcAmtL;
						long gnrlChdEtcAmtL = feeAmountTexts.gnrlChdEtcAmtL;
						long gnrlInfEtcAmtL = feeAmountTexts.gnrlInfEtcAmtL;
						long dtcmAdtEtcAmtL = feeAmountTexts.dtcmAdtEtcAmtL;
						long dtcmChdEtcAmtL = feeAmountTexts.dtcmChdEtcAmtL;
						long dtcmInfEtcAmtL = feeAmountTexts.dtcmInfEtcAmtL;
						String adtQchrgAmtStr = feeAmountTexts.adtQchrgAmtStr;
						String chdQchrgAmtStr = feeAmountTexts.chdQchrgAmtStr;
						String infQchrgAmtStr = feeAmountTexts.infQchrgAmtStr;
						String adtFuelExchgAmtStr = feeAmountTexts.adtFuelExchgAmtStr;
						String chdFuelExchgAmtStr = feeAmountTexts.chdFuelExchgAmtStr;
						String infFuelExchgAmtStr = feeAmountTexts.infFuelExchgAmtStr;
						String adtTaxAmtStr = feeAmountTexts.adtTaxAmtStr;
						String chdTaxAmtStr = feeAmountTexts.chdTaxAmtStr;
						String infTaxAmtStr = feeAmountTexts.infTaxAmtStr;
						String gnrlAdtIsueFeeAmtStr = feeAmountTexts.gnrlAdtIsueFeeAmtStr;
						String gnrlChdIsueFeeAmtStr = feeAmountTexts.gnrlChdIsueFeeAmtStr;
						String gnrlInfIsueFeeAmtStr = feeAmountTexts.gnrlInfIsueFeeAmtStr;
						String dtcmAdtIsueFeeAmtStr = feeAmountTexts.dtcmAdtIsueFeeAmtStr;
						String dtcmChdIsueFeeAmtStr = feeAmountTexts.dtcmChdIsueFeeAmtStr;
						String dtcmInfIsueFeeAmtStr = feeAmountTexts.dtcmInfIsueFeeAmtStr;

						//################ 기본 요금정보 #############################
						long chdBscAmtL = farLstNode.path("chdBscAmt").asLong(0);		//계산용-아동기본요금
						long infBscAmtL = farLstNode.path("infBscAmt").asLong(0);		//계산용-유아기본요금
						long chdBscTotalAmt = chdBscAmtL + gnrlChdEtcAmtL;	//아동기본 최종요금
						long infBscTotalAmt = infBscAmtL + gnrlInfEtcAmtL;	//유아기본 최종요금

						//사이트룰(판매룰/할인이벤트) 텍스트 조립 - 편도결합/비결합 공통 로직은 buildSiteRuleTexts()로 추출
						SiteRuleTexts siteRuleTexts = this.buildSiteRuleTexts(farLstNode);
						sbSiteRuleGnrlAdt = siteRuleTexts.sbSiteRuleGnrlAdt;
						sbSiteRuleGnrlChd = siteRuleTexts.sbSiteRuleGnrlChd;
						sbSiteRuleGnrlInf = siteRuleTexts.sbSiteRuleGnrlInf;
						sbSiteRuleDtcmAdt = siteRuleTexts.sbSiteRuleDtcmAdt;
						sbSiteRuleDtcmChd = siteRuleTexts.sbSiteRuleDtcmChd;
						sbSiteRuleDtcmInf = siteRuleTexts.sbSiteRuleDtcmInf;
						dcEventCd = siteRuleTexts.dcEventCd;
						String dcEventNm = siteRuleTexts.dcEventNm;
						//일반할인요금정보 + 필터용 최종금액 - 편도결합/비결합 공통 로직은 computeGeneralDiscountFareTexts()로 추출
						GeneralDiscountFareTexts generalDiscountFareTexts = this.computeGeneralDiscountFareTexts(farLstNode, feeAmountTexts);
						sbAdtGnrlDcAmtInfoTemp = generalDiscountFareTexts.sbAdtGnrlDcAmtInfoTemp;
						sbChdGnrlDcAmtInfoTemp = generalDiscountFareTexts.sbChdGnrlDcAmtInfoTemp;
						sbInfGnrlDcAmtInfoTemp = generalDiscountFareTexts.sbInfGnrlDcAmtInfoTemp;
						sbAdtDtcmDcAmtInfoTemp = generalDiscountFareTexts.sbAdtDtcmDcAmtInfoTemp;
						sbChdDtcmDcAmtInfoTemp = generalDiscountFareTexts.sbChdDtcmDcAmtInfoTemp;
						sbInfDtcmDcAmtInfoTemp = generalDiscountFareTexts.sbInfDtcmDcAmtInfoTemp;
						adtTamt = generalDiscountFareTexts.adtTamt;
						chdTamt = generalDiscountFareTexts.chdTamt;
						infTamt = generalDiscountFareTexts.infTamt;

						cabinComplexChk = "N";	//좌석결합여부 체크용
						alComplexChk = "N";		//항공사결합여부 체크용
						Map<String, String> cabinMap = new HashMap<String, String>();
						Map<String, String> mktAlMap = new HashMap<String, String>();

						String viaCnt = "";	//경유횟수
						nMaxViaCnt = 0;	//경유횟수
						int nTempViaCnt = 0;//경유횟수
						String viaStr = "";	//경유횟수텍스트

						String cabinType = "";		//좌석등급
						String bookClass = "";		//부킹클래스

						String fareBasis = "";		//fareBasis
						String tktDesg = "";		//tktDesg


						String fareType = "";		//운임유형
						totFltTime = 0;			//총소요시간
						totStopTime = 0;		//총환승시간

						int j=0;
						logger.debug("yt node.itnrLst ---------");
						//################### 여정정보 ##############################################
						for(JsonNode itnrLstNode : farLstNode.path("itnrLst")) {
							//------------------------------
							// Set Response(JSON) - itnrLst
							//------------------------------
							String sItnrSeq = StringUtil.nullConvert(itnrLstNode.path("itnrSeq").toString());	// ROW KEY - 여정순번

							responseItnrLstVo = new Gson().fromJson(itnrLstNode.toString(), SchAirFareResponseItnrLstVo.class);
							responseItnrLstVo.setOrgJson(itnrLstNode.toString());	// json원문
							responseItnrLstVo.setParentRowKey(sParentRowKey1);		// 상위 Row 키
							responseItnrLstVo.setRowKey(sParentRowKey1 +":"+ sItnrSeq);	// 현재 Row 키

							ds_jsonItnrLst.add(responseItnrLstVo);

							String sParentRowKey2 = sFareId + ":" + sItnrSeq; 			// 상위 키 = 운임ID + 여정순번
							//------------------------------

							logger.debug("yt node.itnrLst - for j=" + j++);
							viaCnt = itnrLstNode.path("viaCnt").asText();
							nTempViaCnt = itnrLstNode.path("viaCnt").asInt(0);
							if(Integer.valueOf(viaCnt) > 1) {	//경유횟수:viaCnt=1은 직항을 의미
								int vi = Integer.valueOf(viaCnt) - 1;
								viaStr = "경유"+vi+"회";
							}else {
								viaStr = "직항";
							}

							// 직항, 경우 최종 체크 - 경우가 1개라도 있을경우 직항제외함.
							if(nTempViaCnt > nMaxViaCnt) {
								nMaxViaCnt = nTempViaCnt;
							}

							int itnrSeq = itnrLstNode.path("itnrSeq").asInt(0);
							if(itnrSeq > 1){
								sbViaInfo.append("\n\n" + viaStr);
							}else {
								sbViaInfo.append(viaStr);
							}

							int oneFltTime = this.getMinVal(itnrLstNode.path("totTime").asText("0"));
							totFltTime += oneFltTime;	//총소요시간


							//######################## 비행정보 ##########################################
							int fsi = 1;
							int k=0;
							logger.debug("yt node.itnrLst.fltLst - for k=" + k++);
							for(JsonNode fltLstNode : itnrLstNode.path("fltLst")) {
								//------------------------------
								// Set Response(JSON) - fltLst
								//------------------------------
								String sFltSeq = StringUtil.nullConvert(fltLstNode.path("fltSeq").toString());	// ROW KEY - 비행편순번

								responseFltLstVo = new Gson().fromJson(fltLstNode.toString(), SchAirFareResponseFltLstVo.class);
								responseFltLstVo.setOrgJson(fltLstNode.toString());			// json원문
								responseFltLstVo.setParentRowKey(sParentRowKey2);			// 상위 Row 키
								responseFltLstVo.setRowKey(sParentRowKey2 +":"+ sFltSeq);	// 현재 Row 키

								ds_jsonFltLst.add(responseFltLstVo);

								String sParentRowKey3 = sFareId + ":" + sItnrSeq + ":" + sFltSeq; 			// 상위 키 = 운임ID + 여정 순번 + 비행편순번
								//------------------------------
								// Set Response(JSON) - fltPrcLst
								//------------------------------
								for(JsonNode fltPrcLstNode : fltLstNode.path("fltPrcLst")) {
									responseFltPrcLstVo = new Gson().fromJson(fltPrcLstNode.toString(), SchAirFareResponseFltPrcLstVo.class);
									responseFltPrcLstVo.setOrgJson(fltPrcLstNode.toString());	// json원문
									responseFltPrcLstVo.setParentRowKey(sParentRowKey3);		// 상위 키

									ds_jsonFltPrcLst.add(responseFltPrcLstVo);
								}
								//------------------------------
								// Set Response(JSON) - freeBaggLst
								//------------------------------
								for(JsonNode freeBaggLstNode : fltLstNode.path("freeBaggLst")) {
									responseFreeBaggLstVo = new Gson().fromJson(freeBaggLstNode.toString(), SchAirFareResponseFreeBaggLstVo.class);
									responseFreeBaggLstVo.setOrgJson(freeBaggLstNode.toString());	// json원문
									responseFreeBaggLstVo.setParentRowKey(sParentRowKey3);			// 상위 키

									ds_jsonFreeBaggLst.add(responseFreeBaggLstVo);
								}
								//------------------------------
								// Set Response(JSON) - hidStopLst
								//------------------------------
								for(JsonNode hidStopLstNode : fltLstNode.path("hidStopLst")) {
									responseHidStopLstVoo = new Gson().fromJson(hidStopLstNode.toString(), SchAirFareResponseHidStopLstVo.class);
									responseHidStopLstVoo.setOrgJson(hidStopLstNode.toString());	// json원문
									responseHidStopLstVoo.setParentRowKey(sParentRowKey3);			// 상위 키

									ds_jsonhidStopLst.add(responseHidStopLstVoo);
								}
								//------------------------------

								logger.debug("yt node.itnrLst - for k=" + k++);
								String enterTxt = "";
								String viaEnterTxt = "";
								if(fsi > 1){
									enterTxt = "\n";
									viaEnterTxt = "\n";
								}else {
									if(itnrSeq > 1){
										enterTxt = "\n\n";
									}
								}

								JsonNode fltPrcNode = fltLstNode.path("fltPrcLst").get(0);	//비행프라이싱정보

								//총환승시간
								int oneStopTime = this.getMinVal(fltLstNode.path("stopTime").asText("0"));
								totStopTime += oneStopTime;

								String mktAlCode = StringUtil.nullConvert(fltLstNode.path("mktAlCd"  ).textValue());	//마케팅항공코드
								String mktAlName = StringUtil.nullConvert(fltLstNode.path("mktAlNm"  ).textValue());	//마케팅항공이름
								String oprAlCode = StringUtil.nullConvert(fltLstNode.path("oprAlCode").textValue());	//운항항공코드
								String oprAlName = StringUtil.nullConvert(fltLstNode.path("oprAlName").textValue());	//운항항공이름
								String mktFltNo  = StringUtil.nullConvert(fltLstNode.path("mktFltNo" ).textValue());
                                String eqmtName = StringUtil.nullConvert(fltLstNode.path("eqmtName"  ).textValue());	//기종명
								String oprAlNmDp   = "";
								String oprAlCodeDp = "";

								sbMktAlCodes.append(mktAlCode + ",");
								sbOprAlCodes.append(oprAlCode + ",");
                                sbEqmtNames.append(eqmtName + ",");

								String deptAptCode = StringUtil.nullConvert(fltLstNode.path("deptAptCode").textValue());
								String deptDate = fltLstNode.path("deptDate").asText("0");			//출발일자
								String deptTime = StringUtils.leftPad(fltLstNode.path("deptTime").asText("0"), 4, "0");			//출발시간
								String arrvAptCode = StringUtil.nullConvert(fltLstNode.path("arrvAptCode").textValue());	//도착항공코드
								String arrvDate = fltLstNode.path("arrvDate").asText("0");			//도착일자
								String arrvTime = StringUtils.leftPad(fltLstNode.path("arrvTime").asText("0"), 4, "0");			//도착시간
								cabinType = StringUtil.nullConvert(   fltLstNode.path("cabinType").textValue());	//좌석등급
								bookClass = StringUtil.nullConvert(   fltLstNode.path("bookClass").textValue());	//부킹클래스
								fareBasis = StringUtil.nullConvert(fltPrcNode.path("fareBasis").textValue());	//fareBasis
								tktDesg = StringUtil.nullConvert(fltPrcNode.path("tktDesg").textValue());	//tktDesg
								fareType  = StringUtil.nullConvert(fltPrcNode.path("fareType" ).textValue());	//운임유형
								pasnType  = StringUtil.nullConvert(fltPrcNode.path("pasnType" ).textValue());	//승객구분

								sbPasnTypes.append(pasnType + ",");

								String dpDeptTime = StringUtils.left(deptTime, 2) + ":" + StringUtils.right(deptTime, 2);
								String dpArrvTime = StringUtils.left(arrvTime, 2) + ":" + StringUtils.right(arrvTime, 2);

								sbMktFltNoInfo.append(mktAlCode + mktFltNo + ",");	//결과내 검색을 위한 항공편명 조합
								sbBookClassInfo.append(bookClass + ",");	//결과내 검색을 위한 부킹클래스 조합;
								sbFareBasisInfo.append(fareBasis + ",");	//결과내 검색을 위한 fareBasis 조합;

								//좌석결합여부
								if(!cabinMap.isEmpty() && !cabinMap.containsKey(cabinType)){
									cabinComplexChk = "Y";
								}
								cabinMap.put(cabinType, cabinType);

								//항공사결합여부
								if(!mktAlMap.isEmpty() && !mktAlMap.containsKey(mktAlCode)){
									alComplexChk = "Y";
								}
								mktAlMap.put(mktAlCode, mktAlCode);

								sbCabinTypes.append(cabinType + ",");
								sbFareTypes.append( fareType + ",");

								//좌석등급명
								String cabinTypeNm = "";
								if(seatGradList != null ){
									for(ComComDtlCQcVo scodeVo : seatGradList){
										if(scodeVo.getComDtlCd().equals(cabinType)){
											cabinTypeNm = scodeVo.getComDtlCdNm();
										}
									}
								}
								//운임유형명
								String fareTypeNm = "";
								if(fareTypeList != null ){
									for(ComComDtlCQcVo fcodeVo : fareTypeList){
										if(fcodeVo.getComDtlCd().equals(fareType)){
											fareTypeNm = fcodeVo.getComDtlCdNm();
										}
									}
								}

								sbFltSchdInfo.append(enterTxt + deptAptCode + " " + DateUtil.formatDate(deptDate, "MM/dd") + " " +  dpDeptTime + " => " +	arrvAptCode + " " + DateUtil.formatDate(arrvDate, "MM/dd") + " " + dpArrvTime + "  " + cabinTypeNm + "  " + bookClass);

								sbViaInfo.append(     viaEnterTxt);
								sbAlInfo.append(      enterTxt + mktAlName+(eqmtName == null || eqmtName.isEmpty() ? "" : " (" + eqmtName + ")"));
								sbFltNoInfo.append(   enterTxt + mktAlCode+mktFltNo);
								sbFbInfo.append(      enterTxt + (StringUtils.isEmpty(tktDesg) ? fareBasis : fareBasis + "/" + tktDesg));

								sbFareTypeInfo.append(enterTxt + fareTypeNm);

								// 운항항공사코드가 존재하면서 해당Flight의 마케팅항공사와 다를 경우
								if(!StringUtil.isEmpty(oprAlCode) && !mktAlCode.equals(oprAlCode)){
									oprAlNmDp   = "\n<fc v='red'>" + oprAlName + "</fc>";
									oprAlCodeDp = "\n<fc v='red'>" + oprAlCode + "</fc>";
									sbViaInfo.append(    "\n");
									sbAlInfo.append(     oprAlNmDp);
									sbFltNoInfo.append(  oprAlCodeDp);
									sbFltSchdInfo.append("\n");
									sbFbInfo.append(     "\n");
									sbFareTypeInfo.append("\n");
								}

								fsi++;

								//################# SEG단위 필터설정 #########################################
								//개별탭-필터설정 : 마케팅항공사, 운항항공사, 좌석유형, 운임유형, 승객유형
								this.setFilterMap(fareFltrMap, newSplyCd, mktAlCode, mktAlName  , adtTamt, FltrType.MKT_AL_CODES);
								this.setFilterMap(fareFltrMap, newSplyCd, oprAlCode, oprAlName  , adtTamt, FltrType.OPR_AL_CODES);
								this.setFilterMap(fareFltrMap, newSplyCd, cabinType, cabinTypeNm, 0L     , FltrType.CABIN_TYPES);
								this.setFilterMap(fareFltrMap, newSplyCd, fareType , fareTypeNm , 0L     , FltrType.FARE_TYPES);
								this.setFilterMap(fareFltrMap, newSplyCd, pasnType , pasnType   , 0L     , FltrType.PASN_TYPES);

								//통합탭-필터설정 : 마케팅항공사, 운항항공사, 좌석유형, 운임유형, 승객유형
								this.setFilterMap(fareFltrMap, ALL_TAP, mktAlCode, mktAlName  , adtTamt, FltrType.MKT_AL_CODES);
								this.setFilterMap(fareFltrMap, ALL_TAP, oprAlCode, oprAlName  , adtTamt, FltrType.OPR_AL_CODES);
								this.setFilterMap(fareFltrMap, ALL_TAP, cabinType, cabinTypeNm, 0L     , FltrType.CABIN_TYPES);
								this.setFilterMap(fareFltrMap, ALL_TAP, fareType , fareTypeNm , 0L     , FltrType.FARE_TYPES);
								this.setFilterMap(fareFltrMap, ALL_TAP, pasnType , pasnType   , 0L     , FltrType.PASN_TYPES);
								int acctCodeSetSize = fltPrcNode.path("acctCodeSet").size();

								//if(fltPrcNode.path("acctCodeSet").textValue() != null){
								if(acctCodeSetSize > 0) {
									ArrayNode acctNode = (ArrayNode) fltPrcNode.path("acctCodeSet");
									for(JsonNode acctCodeNode : acctNode){
										String accntCode = acctCodeNode.asText();
										sbAcctCodes.append(accntCode + ",");

										//개별탭-필터설정 : accountCode
										this.setFilterMap(fareFltrMap, newSplyCd, accntCode, accntCode, 0L, FltrType.ACCT_CODES);

										//개별탭-필터설정 : accountCode
										this.setFilterMap(fareFltrMap, ALL_TAP, accntCode, accntCode, 0L, FltrType.ACCT_CODES);
									}
								}
								//################ SEG단위 필터설정 ###############################################


							}	//비행정보
						}	//여정정보

						//-------------------------------------
						// 직항, 경유 필터 설정 - 운임별로 설정함.
						//-------------------------------------
						String viaStr2 = "";
						if(nMaxViaCnt > 1) {	//경유횟수:viaCnt=1은 직항을 의미
							int vi = nMaxViaCnt - 1;
							viaStr2 = "경유"+vi+"회";
						}else {
							viaStr2 = "직항";
						}

						this.setFilterMap(fareFltrMap, newSplyCd, String.valueOf(nMaxViaCnt), viaStr2, adtTamt, FltrType.VIA_CNT);	// 개별탭-필터설정 : 직항/경유
						this.setFilterMap(fareFltrMap, ALL_TAP  , String.valueOf(nMaxViaCnt), viaStr2, adtTamt, FltrType.VIA_CNT);	// 통합탭-필터설정 : 직항/경유
						//-------------------------------------
						// 노출제어 추가
						//-------------------------------------
						this.setFilterMap(fareFltrMap, newSplyCd, sIsueAirlRuleFarExlsTrgtYn, sIsueAirlRuleFarExlsTrgtYn, 0L     , FltrType.ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN);
						this.setFilterMap(fareFltrMap, newSplyCd, sMCTRuleFarExlsTrgtYn     , sMCTRuleFarExlsTrgtYn     , 0L     , FltrType.MCT_RULE_FAR_EXLS_TRGT_YN      );
						this.setFilterMap(fareFltrMap, ALL_TAP  , sIsueAirlRuleFarExlsTrgtYn, sIsueAirlRuleFarExlsTrgtYn, 0L     , FltrType.ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN);
						this.setFilterMap(fareFltrMap, ALL_TAP  , sMCTRuleFarExlsTrgtYn     , sMCTRuleFarExlsTrgtYn     , 0L     , FltrType.MCT_RULE_FAR_EXLS_TRGT_YN      );
						//-------------------------------------

						sbViaInfo.append(    "\n");	// 다음줄과 공백띄우기 위해 삽입
						sbAlInfo.append(     "\n");	// 다음줄과 공백띄우기 위해 삽입
						sbFltNoInfo.append(  "\n");	// 다음줄과 공백띄우기 위해 삽입
						sbFltSchdInfo.append("\n");	// 다음줄과 공백띄우기 위해 삽입
						sbFbInfo.append(     "\n");	// 다음줄과 공백띄우기 위해 삽입

						String pftktSeq      =            StringUtil.nullConvert(farLstNode.path("pftktSeq"     ).textValue());	                                // PF운임룰번호
						String pfTktYn       = "Y".equals(StringUtil.nullConvert(farLstNode.path("pftktYn"      ).textValue())) ? "[PF] (No."+pftktSeq+") " : "";	// PF티켓여부
						imdtPayPsblYn =            StringUtil.nullConvert(farLstNode.path("imdtPayPsblYn").textValue());			                  		//즉시결제가능여부
						gnrlEventCds  = "";	//필터용
						String gnrlEventCdDp = "";	//화면표시용
						sbGnrlEventCdDp = new StringBuilder("");	//화면표시용

						String gnrlEventCd1  = StringUtil.nullConvert(farLstNode.path("gnrlEvent1Cd").textValue());	//일반이벤트코드1
						String gnrlEventCd2  = StringUtil.nullConvert(farLstNode.path("gnrlEvent2Cd").textValue());	//일반이벤트코드2
						String gnrlEvent1Nm  = StringUtil.nullConvert(farLstNode.path("gnrlEvent1Nm").textValue());	//일반이벤트_1_명
						String gnrlEvent2Nm  = StringUtil.nullConvert(farLstNode.path("gnrlEvent2Nm").textValue());	//일반이벤트_2_명
						String atmtIsueYn    = StringUtil.nullConvert(farLstNode.path("atmtIsueYn"  ).textValue());	//자동발권여부 - 20200311 : '자동발권여부' > 'GDS자동발권가능여부'로 변경
						String ntytFixFarDvCd=            StringUtil.nullConvert(farLstNode.path("ntytFixFarDvCd").textValue());					// 미확정운임구분코드
						fixFarYn      =  "".equals(StringUtil.nullConvert(farLstNode.path("ntytFixFarDvCd").textValue())) ? "Y" : "N";		// 확정운임여부

//					String bestFarYn     = "Y".equals(StringUtil.nullConvert(farLstNode.path("bestFarYn"     ).textValue())) ? "[BEST] " : "";	// best운임여부
						String bestFarYn     =            StringUtil.nullConvert(farLstNode.path("bestFarYn"     ).textValue());					// best운임여부
						int    bestFarRn     =                                   farLstNode.path("bestFarRn"     ).asInt(0);						// 20191031 : best 관련 수정(bestFarRn 추가)
						if("Y".equals(bestFarYn)) {
							if(0 != bestFarRn) {
								bestFarYn = "[BEST] (Rank. " + bestFarRn + ") ";
							} else {
								bestFarYn = "[BEST] (Rank -) ";
							}
						} else {
							bestFarYn = "";
						}


						String eventId =            StringUtil.nullConvert(farLstNode.path("eventId").textValue());					//eventId
						gnrlEventCds = gnrlEventCd1+","+gnrlEventCd2;

						if(!StringUtil.isEmpty(gnrlEventCd1)) {
							sbGnrlEventCdDp.append("No."+eventId+" - "+gnrlEventCd1+" "+gnrlEvent1Nm);
						}
						if(!StringUtil.isEmpty(gnrlEventCd2)) {
							if(!StringUtil.isEmpty(sbGnrlEventCdDp.toString())) {
								sbGnrlEventCdDp.append(","+ gnrlEventCd2+" "+gnrlEvent2Nm);
							} else {
								sbGnrlEventCdDp.append("No."+eventId+" - "+gnrlEventCd2+" "+gnrlEvent2Nm);
							}
						}
						if(StringUtil.isEmpty(sbGnrlEventCdDp.toString())) {
							gnrlEventCdDp = "일반이벤트 - ";
						} else {
							gnrlEventCdDp = "일반이벤트(" + sbGnrlEventCdDp.toString() + ")";
						}
						//########## 발권항공사 setting #####################

						// yt : 발권항공사 쿼리 시행됨 - 성능개선 필요.

						tktAlCode = StringUtil.nullConvert(farLstNode.path("tktAlCode").textValue());	//발권항공사코드
						tktAlName = StringUtil.nullConvert(farLstNode.path("tktAlName").textValue());	//발권항공사명

						//개별탭-필터설정 : 발권항공사, 항공사결합, 좌석결합, 즉시결제가능여부, 운임확정여부, 일반이벤트코드, 할인이벤트코드, 금액, 총소요시간, 총환승시간
						this.setFilterMap(fareFltrMap, newSplyCd, tktAlCode      , tktAlName      , adtTamt, FltrType.TKT_AL_CODE);
						this.setFilterMap(fareFltrMap, newSplyCd, alComplexChk   , alComplexChk   , 0L     , FltrType.AL_COMPLEX);
						this.setFilterMap(fareFltrMap, newSplyCd, cabinComplexChk, cabinComplexChk, 0L     , FltrType.CABIN_COMPLEX);
						this.setFilterMap(fareFltrMap, newSplyCd, imdtPayPsblYn  , imdtPayPsblYn  , 0L     , FltrType.IMDT_PAY_PSBL_YN);
						this.setFilterMap(fareFltrMap, newSplyCd, fixFarYn       , fixFarYn       , 0L     , FltrType.FIX_FAR_YN);
						this.setFilterMap(fareFltrMap, newSplyCd, gnrlEventCd1   , gnrlEvent1Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
						this.setFilterMap(fareFltrMap, newSplyCd, gnrlEventCd2   , gnrlEvent2Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
						this.setFilterMap(fareFltrMap, newSplyCd, dcEventCd      , dcEventNm      , 0L     , FltrType.DC_EVENT_CODE);
						this.setFilterMap(fareFltrMap, newSplyCd, FltrType.ADT_T_AMT.getFltrFld(), FltrType.ADT_T_AMT.getFltrFld(), adtTamt, FltrType.ADT_T_AMT);
						this.setTimeFilterMap(fareFltrMap, newSplyCd, totFltTime, FltrType.TOT_TIME);
						this.setTimeFilterMap(fareFltrMap, newSplyCd, totStopTime, FltrType.STOP_TIME);

						//통합탭-필터설정 : 발권항공사, 항공사결합, 좌석결합, 즉시결제가능여부, 운임확정여부, 일반이벤트코드, 할인이벤트코드, 금액, 총소요시간, 총환승시간
						this.setFilterMap(fareFltrMap, ALL_TAP, tktAlCode      , tktAlName      , adtTamt, FltrType.TKT_AL_CODE);
						this.setFilterMap(fareFltrMap, ALL_TAP, alComplexChk   , alComplexChk   , 0L     , FltrType.AL_COMPLEX);
						this.setFilterMap(fareFltrMap, ALL_TAP, cabinComplexChk, cabinComplexChk, 0L     , FltrType.CABIN_COMPLEX);
						this.setFilterMap(fareFltrMap, ALL_TAP, imdtPayPsblYn  , imdtPayPsblYn  , 0L     , FltrType.IMDT_PAY_PSBL_YN);
						this.setFilterMap(fareFltrMap, ALL_TAP, fixFarYn       , fixFarYn       , 0L     , FltrType.FIX_FAR_YN);
						this.setFilterMap(fareFltrMap, ALL_TAP, gnrlEventCd1   , gnrlEvent1Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
						this.setFilterMap(fareFltrMap, ALL_TAP, gnrlEventCd2   , gnrlEvent2Nm   , 0L     , FltrType.GNRL_EVENT_CODE);
						this.setFilterMap(fareFltrMap, ALL_TAP, dcEventCd      , dcEventNm      , 0L     , FltrType.DC_EVENT_CODE);
						this.setFilterMap(fareFltrMap, ALL_TAP, FltrType.ADT_T_AMT.getFltrFld(), FltrType.ADT_T_AMT.getFltrFld(), adtTamt, FltrType.ADT_T_AMT);
						this.setTimeFilterMap(fareFltrMap, ALL_TAP, totFltTime , FltrType.TOT_TIME);
						this.setTimeFilterMap(fareFltrMap, ALL_TAP, totStopTime, FltrType.STOP_TIME);


						//발권항공사룰운임제외대상여부/MCT룰운임제외대상여부
						String isueAlirlRuleFarExlsTrgtYn = "Y".equals(StringUtil.nullConvert(farLstNode.path("isueAlirlRuleFarExlsTrgtYn").textValue())) ? "발권항공사룰운임제외대상 " : "";
						String mctRuleFarExlsTrgtYn       = "Y".equals(StringUtil.nullConvert(farLstNode.path("mctRuleFarExlsTrgtYn"      ).textValue())) ? "MCT룰운임제외대상 " : "";
						String exlsTargnYnStr = isueAlirlRuleFarExlsTrgtYn+mctRuleFarExlsTrgtYn;

						if(!"".equals(exlsTargnYnStr)) {
							exlsTargnYnStr = exlsTargnYnStr + "\n";
						}

						//특별적립마일리지 텍스트 - 편도결합/비결합 공통 로직은 buildSpclColtMlgTexts()로 추출
						SpclColtMlgTexts spclColtMlgTexts = this.buildSpclColtMlgTexts(farLstNode);
						sbGnrlAdtSpclColtMlgAmt = spclColtMlgTexts.sbGnrlAdtSpclColtMlgAmt;
						sbGnrlChdSpclColtMlgAmt = spclColtMlgTexts.sbGnrlChdSpclColtMlgAmt;
						sbDtcmAdtSpclColtMlgAmt = spclColtMlgTexts.sbDtcmAdtSpclColtMlgAmt;
						sbDtcmChdSpclColtMlgAmt = spclColtMlgTexts.sbDtcmChdSpclColtMlgAmt;


						sbAdtGnrlDcAmtInfo = new StringBuilder("");
						sbChdGnrlDcAmtInfo = new StringBuilder("");
						sbInfGnrlDcAmtInfo = new StringBuilder("");
						sbAdtDtcmDcAmtInfo = new StringBuilder("");
						sbChdDtcmDcAmtInfo = new StringBuilder("");
						sbInfDtcmDcAmtInfo = new StringBuilder("");

						sbAdtGnrlDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "일반 적용가 ADT " + sbAdtGnrlDcAmtInfoTemp.toString());
						sbChdGnrlDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "일반 적용가 CHD " + sbChdGnrlDcAmtInfoTemp.toString());
						sbInfGnrlDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "일반 적용가 INF " + sbInfGnrlDcAmtInfoTemp.toString());
						sbAdtDtcmDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "닷컴 적용가 ADT " + sbAdtDtcmDcAmtInfoTemp.toString());
						sbChdDtcmDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "닷컴 적용가 CHD " + sbChdDtcmDcAmtInfoTemp.toString());
						sbInfDtcmDcAmtInfo.append("\n" + pfTktYn + bestFarYn + "닷컴 적용가 INF " + sbInfDtcmDcAmtInfoTemp.toString());

						//카드프로모션정보 - 편도결합/비결합 공통 로직은 buildCardPromotionTexts()로 추출
						CardPromotionTexts cardPromotionTexts = this.buildCardPromotionTexts(farLstNode, feeAmountTexts, pasnType, newSplyCd, fareFltrMap, sbGnrlCardPromId, sbGnrlCardPromEventCd, sbGnrlCardNm, sbGnrlCardDcInfo, sbGnrlCardDcAplAmt, sbGnrlCardDcTotalAmt, sbDtcmCardPromId, sbDtcmCardPromEventCd, sbDtcmCardNm, sbDtcmCardDcInfo, sbDtcmCardDcAplAmt, sbDtcmCardDcTotalAmt, sbCardPromIds);
						sbCardGnrlAdt = cardPromotionTexts.sbCardGnrlAdt;
						sbCardGnrlChd = cardPromotionTexts.sbCardGnrlChd;
						sbCardGnrlInf = cardPromotionTexts.sbCardGnrlInf;
						sbCardDtcmAdt = cardPromotionTexts.sbCardDtcmAdt;
						sbCardDtcmChd = cardPromotionTexts.sbCardDtcmChd;
						sbCardDtcmInf = cardPromotionTexts.sbCardDtcmInf;
						isExistCardGnrlInfo = cardPromotionTexts.isExistCardGnrlInfo;
						isExistCardDtcmInfo = cardPromotionTexts.isExistCardDtcmInfo;


						//발권수수료(TASF) 텍스트 - 편도결합/비결합 공통 로직은 buildTasfFeeTexts()로 추출
						TasfFeeTexts tasfFeeTexts = this.buildTasfFeeTexts(farLstNode);
						sbIsueFeeGnrlAdt = tasfFeeTexts.sbIsueFeeGnrlAdt;
						sbIsueFeeGnrlChd = tasfFeeTexts.sbIsueFeeGnrlChd;
						sbIsueFeeGnrlInf = tasfFeeTexts.sbIsueFeeGnrlInf;
						sbIsueFeeDtcmAdt = tasfFeeTexts.sbIsueFeeDtcmAdt;
						sbIsueFeeDtcmChd = tasfFeeTexts.sbIsueFeeDtcmChd;
						sbIsueFeeDtcmInf = tasfFeeTexts.sbIsueFeeDtcmInf;


						//------------------------------------
						// 공급코드, 발권항공사, 여정타입..
						//------------------------------------
						String sSplyCdFm          = farLstNode.path("splyCd"    ).textValue();
						String sCurrCode          = StringUtil.nullConvert(farLstNode.path("currCode"      ).asText());
						String sAdtBscAmtFm       = sBasicPasnTypeNmAdt +NumberUtil.formatNumber(String.valueOf(farLstNode.path("adtBscAmt"      ).asLong(0)), "#,###,###") + " " + sCurrCode;
						String sChdBscAmtFm       = sBasicPasnTypeNmChd +NumberUtil.formatNumber(String.valueOf(farLstNode.path("chdBscAmt"      ).asLong(0)), "#,###,###") + " " + sCurrCode;
						String sInfBscAmtFm       = sBasicPasnTypeNmInf +NumberUtil.formatNumber(String.valueOf(farLstNode.path("infBscAmt"      ).asLong(0)), "#,###,###") + " " + sCurrCode;
						String sAdtQchrgAmtFm     =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("adtQchrgAmt"    ).asLong(0)), "#,###,###");
						String sChdQchrgAmtFm     =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("chdQchrgAmt"    ).asLong(0)), "#,###,###");
						String sInfQchrgAmtFm     =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("infQchrgAmt"    ).asLong(0)), "#,###,###");
						String sAdtFuelExchgAmtFm =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("adtFuelExchgAmt").asLong(0)), "#,###,###");
						String sChdFuelExchgAmtFm =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("chdFuelExchgAmt").asLong(0)), "#,###,###");
						String sInfFuelExchgAmtFm =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("infFuelExchgAmt").asLong(0)), "#,###,###");
						String sAdtTaxAmt         =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("adtTaxAmt"      ).asLong(0)), "#,###,###");
						String sChdTaxAmt         =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("chdTaxAmt"      ).asLong(0)), "#,###,###");
						String sInfTaxAmt         =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("infTaxAmt"      ).asLong(0)), "#,###,###");
						String sAdtTamtFm         =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("adtTamt"        ).asLong(0)), "#,###,###");
						String sChdTamtFm         =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("chdTamt"        ).asLong(0)), "#,###,###");
						String sInfTamtFm         =         NumberUtil.formatNumber(String.valueOf(farLstNode.path("infTamt"        ).asLong(0)), "#,###,###");

//					String sSplyInfo = "";
						sbSplyInfo = new StringBuilder("");
						sbSplyInfo.append("공급코드 "      + sSplyCdFm);

						// 발권항공사 : bizCom.isDebug == true, isueAirlRuleFarExlsTrgtYn == "Y" 일 경우 '(노출제어)' 붙임
						if(bIsDebug && "Y".equals(sIsueAirlRuleFarExlsTrgtYn)) {
							sbSplyInfo.append(" 발권항공사 "   + tktAlCode + " <fc v='red'>(노출제어)</fc>");
						} else {
							sbSplyInfo.append(" 발권항공사 "   + tktAlCode);
						}

						// 미노출 Text 표시
						if(StringUtils.isNotEmpty(sFarExlsResnCd)){
							String resultText = sFarExlsResnText;

							if(StringUtils.isNotEmpty(resultText)){
								sbSplyInfo.append(" <fc v='#D2006E'>("+resultText+")</fc>");
							}
						}

						// 룰셋여정타입 : bizCom.isDebug == true, MCTRuleFarExlsTrgtYn == "Y" 일 경우 '(MCT노출제어)' 붙임
						if(bIsDebug && "Y".equals(sMCTRuleFarExlsTrgtYn)) {
							sbSplyInfo.append(" 룰셋여정타입 " + gdsItnrTypeCd + " <fc v='red'>(MCT노출제어)</fc>");
						} else {
							sbSplyInfo.append(" 룰셋여정타입 " + gdsItnrTypeCd);
						}

						String sBscAmtFmAdt = "\n기본운임 "   + sAdtBscAmtFm + " (Q "    + sAdtQchrgAmtFm + " FUEL " + sAdtFuelExchgAmtFm + " TAX "  + sAdtTaxAmt + ")" + " Total " + sAdtTamtFm;
						String sBscAmtFmChd = "\n기본운임 "   + sChdBscAmtFm + " (Q "    + sChdQchrgAmtFm + " FUEL " + sChdFuelExchgAmtFm + " TAX "  + sChdTaxAmt + ")" + " Total " + sChdTamtFm;
						String sBscAmtFmInf = "\n기본운임 "   + sInfBscAmtFm + " (Q "    + sInfQchrgAmtFm + " FUEL " + sInfFuelExchgAmtFm + " TAX "  + sInfTaxAmt + ")" + " Total " + sInfTamtFm;
						//------------------------------------
						// 대리점 커미션정보
						//------------------------------------
						sbAgtCmsnGnrlTitle = new StringBuilder("");
						sbAgtCmsnDtcmTitle = new StringBuilder("");
						sbAgtCmsnGnrlAdt   = new StringBuilder("");
						sbAgtCmsnGnrlChd   = new StringBuilder("");
						sbAgtCmsnGnrlInf   = new StringBuilder("");
						sbAgtCmsnDtcmAdt   = new StringBuilder("");
						sbAgtCmsnDtcmChd   = new StringBuilder("");
						sbAgtCmsnDtcmInf   = new StringBuilder("");

						String agtCmsnSeq = StringUtil.nullConvert(farLstNode.path("agtCmsnSeq").asText());

						if(StringUtil.isEmpty(agtCmsnSeq)) {
							sbAgtCmsnGnrlTitle.append("\n일반 대리점컴 ");
							sbAgtCmsnGnrlAdt.append(  " ADT - ");
							sbAgtCmsnGnrlChd.append(  " CHD - ");
							sbAgtCmsnGnrlInf.append(  " INF - ");
							sbAgtCmsnDtcmTitle.append("\n닷컴 대리점컴 ");
							sbAgtCmsnDtcmAdt.append(  " ADT - ");
							sbAgtCmsnDtcmChd.append(  " CHD - ");
							sbAgtCmsnDtcmInf.append(  " INF - ");
						}else {
							String agtCmsnAdtCmsnTrf = StringUtil.nullConvert(farLstNode.path("agtCmsnAdtCmsnTrf").asText());	// 대리점커미션성인커미션오율
							String agtCmsnChdCmsnTrf = StringUtil.nullConvert(farLstNode.path("agtCmsnChdCmsnTrf").asText());	// 대리점커미션아동커미션오율
							String agtCmsnInfCmsnTrf = StringUtil.nullConvert(farLstNode.path("agtCmsnInfCmsnTrf").asText());	// 대리점커미션유아커미션오율
							String agtCmsnGnrlAdtAmt = StringUtil.nullConvert(farLstNode.path("agtCmsnGnrlAdtAmt").asText());	// 대리점커미션일반성인금액
							String agtCmsnGnrlChdAmt = StringUtil.nullConvert(farLstNode.path("agtCmsnGnrlChdAmt").asText());	// 대리점커미션일반아동금액
							String agtCmsnGnrlInfAmt = StringUtil.nullConvert(farLstNode.path("agtCmsnGnrlInfAmt").asText());	// 대리점커미션일반유아금액
							String agtCmsnDtcmAdtAmt = StringUtil.nullConvert(farLstNode.path("agtCmsnDtcmAdtAmt").asText());	// 대리점커미션닷컴성인금액
							String agtCmsnDtcmChdAmt = StringUtil.nullConvert(farLstNode.path("agtCmsnDtcmChdAmt").asText());	// 대리점커미션닷컴아동금액
							String agtCmsnDtcmInfAmt = StringUtil.nullConvert(farLstNode.path("agtCmsnDtcmInfAmt").asText());	// 대리점커미션닷컴유아금액

							// 대리점커미션적용방식 : R - 정률, A - 정액
							String agtCmsnAplMthdCd = StringUtil.nullConvert(farLstNode.path("agtCmsnAplMthdCd"   ).asText());
							if("R".equals(agtCmsnAplMthdCd)) {
								//------------------------
								// 정률
								//------------------------
								sbAgtCmsnGnrlTitle.append("\n일반 대리점컴 (No."+agtCmsnSeq+")");
								sbAgtCmsnGnrlAdt.append( " ADT R " + agtCmsnAdtCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnGnrlAdtAmt, "#,###,###"));
								sbAgtCmsnGnrlChd.append(   " CHD R " + agtCmsnChdCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnGnrlChdAmt, "#,###,###"));
								sbAgtCmsnGnrlInf.append(   " INF R " + agtCmsnInfCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnGnrlInfAmt, "#,###,###"));
								sbAgtCmsnDtcmTitle.append("\n닷컴 대리점컴 (No."+agtCmsnSeq+")");
								sbAgtCmsnDtcmAdt.append(   " ADT R " + agtCmsnAdtCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnDtcmAdtAmt, "#,###,###"));
								sbAgtCmsnDtcmChd.append(   " CHD R " + agtCmsnChdCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnDtcmChdAmt, "#,###,###"));
								sbAgtCmsnDtcmInf.append(   " INF R " + agtCmsnInfCmsnTrf +"% / " + NumberUtil.formatNumber(agtCmsnDtcmInfAmt, "#,###,###"));
							} else if("A".equals(agtCmsnAplMthdCd)) {
								//------------------------
								// 정액
								//------------------------
								sbAgtCmsnGnrlTitle.append("\n일반 대리점컴 (No."+agtCmsnSeq+")");
								sbAgtCmsnGnrlAdt.append(   " ADT A / " + NumberUtil.formatNumber(agtCmsnGnrlAdtAmt, "#,###,###"));
								sbAgtCmsnGnrlChd.append(   " CHD A / " + NumberUtil.formatNumber(agtCmsnGnrlChdAmt, "#,###,###"));
								sbAgtCmsnGnrlInf.append(   " INF A / " + NumberUtil.formatNumber(agtCmsnGnrlInfAmt, "#,###,###"));
								sbAgtCmsnDtcmTitle.append("\n닷컴 대리점컴 (No."+agtCmsnSeq+")");
								sbAgtCmsnDtcmAdt.append(   " ADT A / " + NumberUtil.formatNumber(agtCmsnDtcmAdtAmt, "#,###,###"));
								sbAgtCmsnDtcmChd.append(   " CHD A / " + NumberUtil.formatNumber(agtCmsnDtcmChdAmt, "#,###,###"));
								sbAgtCmsnDtcmInf.append(   " INF A / " + NumberUtil.formatNumber(agtCmsnDtcmInfAmt, "#,###,###"));
							}
						}

						//--------------------------------------
						// 발권수수료-성인원화전체금액
						//--------------------------------------
						JsonNode feeDtlNode = farLstNode.path("feeDtl");
						String gnrlAdtKrwAllAmt = !"".equals(feeDtlNode.path("gnrlAdtKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("gnrlAdtKrwAllAmt").asText(), "#,###,###") : "";
						String gnrlChdKrwAllAmt = !"".equals(feeDtlNode.path("gnrlChdKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("gnrlChdKrwAllAmt").asText(), "#,###,###") : "";
						String gnrlInfKrwAllAmt = !"".equals(feeDtlNode.path("gnrlInfKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("gnrlInfKrwAllAmt").asText(), "#,###,###") : "";
						String dtcmAdtKrwAllAmt = !"".equals(feeDtlNode.path("dtcmAdtKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("dtcmAdtKrwAllAmt").asText(), "#,###,###") : "";
						String dtcmChdKrwAllAmt = !"".equals(feeDtlNode.path("dtcmChdKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("dtcmChdKrwAllAmt").asText(), "#,###,###") : "";
						String dtcmInfKrwAllAmt = !"".equals(feeDtlNode.path("dtcmInfKrwAllAmt").asText()) ? " Total " + NumberUtil.formatNumber(feeDtlNode.path("dtcmInfKrwAllAmt").asText(), "#,###,###") : "";

						sbIsueFeeGnrlAdt.append(gnrlAdtKrwAllAmt);
						sbIsueFeeGnrlChd.append(gnrlChdKrwAllAmt);
						sbIsueFeeGnrlInf.append(gnrlInfKrwAllAmt);
						sbIsueFeeDtcmAdt.append(dtcmAdtKrwAllAmt);
						sbIsueFeeDtcmChd.append(dtcmChdKrwAllAmt);
						sbIsueFeeDtcmInf.append(dtcmInfKrwAllAmt);
						//--------------------------------------

						//###### 운임기본정보setting #####
						sbTmpFareBscInfo = new StringBuilder("");
						sbEtcInfo = new StringBuilder("");

						//운임확정정보
						if("Y".equals(fixFarYn )){
							sbTmpFareBscInfo.append("운임확정 ");
							sbEtcInfo.append(      " 운임확정");
						}else {
							sbTmpFareBscInfo.append("운임미확정 ");
							sbEtcInfo.append(       " 운임미확정 ("+ntytFixFarDvCd+")");
						}
						sbEtcInfo.append(" GDS자동발권가능여부" + atmtIsueYn);	// 20200311 : '자동발권여부' > 'GDS자동발권가능여부'로 변경


						//즉시결제가능여부
						if("Y".equals(imdtPayPsblYn)){
							sbTmpFareBscInfo.append("즉시결제 ");
						}else {
							sbTmpFareBscInfo.append("추후결제 ");
						}

						if("Y".equals(imdtPayPsblYn)){
							sbEtcInfo.append(" 즉시결제Y");
						}else {
							sbEtcInfo.append(" 즉시결제N");
						}

						//결제타입
						String payTypeCd   = StringUtil.nullConvert(farLstNode.path("payTypeCd"  ).textValue());	//결제유형코드
						String payTypeCdNm = StringUtil.nullConvert(farLstNode.path("payTypeCdNm").textValue());	//결제유형코드명
						sbTmpFareBscInfo.append("결제타입 "+payTypeCd+"["+payTypeCdNm+"]"+" ");

						sbEtcInfo.append(" 결제타입 ("+payTypeCd+")"+payTypeCdNm);

						String maxTktDate   = StringUtil.nullConvert(farLstNode.path("maxTktDate"  ).textValue());	//발권마감일자
						String maxTktTime   = StringUtil.nullConvert(farLstNode.path("maxTktTime"  ).textValue());	//발권마감시간

						if (StringUtils.isNotEmpty(maxTktDate)) {
							maxTktDate = DateUtil.formatDate(maxTktDate, "yyyy-MM-dd");
						}

						sbEtcInfo.append(" 발권마감일 " + maxTktDate + " " + maxTktTime);

						String evdnDocNcstYn   = StringUtil.nullConvert(farLstNode.path("evdnDocNcstYn"  ).textValue());	//증빙문서필요여부
						sbEtcInfo.append(" 룰셋증빙서류필요여부" + evdnDocNcstYn);	// 20191025 : 룰셋증빙서류필요여부

						if("N".equals(apiSupCode) || "L".equals(apiSupCode) || "R".equals(apiSupCode)|| "P".equals(apiSupCode)){//NDC 일때만 Upsell 여부 추가
							String sUpsellTF = farLstNode.path("upsellPsblYn").textValue();
							sbEtcInfo.append("\nUPSELL 공급여부" + sUpsellTF);	// 20240319 : upsell 공급여부
						}

						if("G".equals(apiSupCode) || "M".equals(apiSupCode)){//갈릴레오,아마데우스 일때만 페어패밀리 여부 추가
							String sFareFmlYn = farLstNode.path("fareFmlYn").textValue();
							if("Y".equals(sFareFmlYn)){
								sbEtcInfo.append("\n페어패밀리 여부" + sFareFmlYn);	// 20240703 : 페어패밀리 여부
							}
						}

						fareBscInfo = "";
						sbFareBscInfo = new StringBuilder("");
						sbFareBscInfo.append("".equals(sbIsueFeeGnrlAdt.toString()) ? "" : sbIsueFeeGnrlAdt.toString() + "\n");
						sbFareBscInfo.append("".equals(sbIsueFeeDtcmAdt.toString()) ? sbTmpFareBscInfo.toString() : sbIsueFeeDtcmAdt.toString() + "\n" + sbTmpFareBscInfo.toString());

						//더보기 텍스트
						moreTxt = "";
						if(!StringUtil.isEmpty(sbAdtDtcmDcAmtInfo.toString()) || "Y".equals(isExistCardGnrlInfo) || "Y".equals(isExistCardDtcmInfo)){
							moreTxt = "+더보기";

							fareBscInfo = sbAdtDtcmDcAmtInfo.toString() + "\n" + sbFareBscInfo.toString();
						} else {
							fareBscInfo = sbFareBscInfo.toString();
						}

						//------------------------------------
						// 기본보기 - 공급코드, 발권항공사, 여정타입..  + 기타정보(운임확정, 자동발권여부, 즉시결제..)
						//------------------------------------
						sbDefaultInfoFm.append(sbSplyInfo.toString() + sbEtcInfo.toString());
						//------------------------------------
						// 기본보기 - 기본운임(Adt + Chd + Inf)
						//------------------------------------
						if(bCntAdt) sbDefaultInfoFm.append(sBscAmtFmAdt);
						if(bCntChd) sbDefaultInfoFm.append(sBscAmtFmChd);
						if(bCntInf) sbDefaultInfoFm.append(sBscAmtFmInf);
						//------------------------------------
						// 기본보기 - 판매룰 일반(Adt + Chd + Inf)
						//------------------------------------
						if(bCntAdt) sbDefaultInfoFm.append(sbSiteRuleGnrlAdt.toString());
						if(bCntChd) sbDefaultInfoFm.append(sbSiteRuleGnrlChd.toString());
						if(bCntInf) sbDefaultInfoFm.append(sbSiteRuleGnrlInf.toString());
						//------------------------------------
						// 기본보기 - PF, BEST..일반 (Adt + Chd + Inf)
						//------------------------------------
						if(bCntAdt) sbDefaultInfoFm.append(sbAdtGnrlDcAmtInfo.toString());
						if(bCntChd) sbDefaultInfoFm.append(sbChdGnrlDcAmtInfo.toString());
						if(bCntInf) sbDefaultInfoFm.append(sbInfGnrlDcAmtInfo.toString());
						//------------------------------------
						// 기본보기 - TASF 일반
						//------------------------------------
						if(bCntAdt) sbDefaultInfoFm.append(sbIsueFeeGnrlAdt.toString());
						if(bCntChd) sbDefaultInfoFm.append(sbIsueFeeGnrlChd.toString());
						if(bCntInf) sbDefaultInfoFm.append(sbIsueFeeGnrlInf.toString());
						//------------------------------------

						//------------------------------------
						// 더보기 - 일반이벤트(이벤트1, 이벤트2)
						//------------------------------------
						sbMoreViewInfoFm.append(gnrlEventCdDp);
						//------------------------------------
						// 더보기 - 특별적립마일리지 일반(Adt + Chd)
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbGnrlAdtSpclColtMlgAmt.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbGnrlChdSpclColtMlgAmt.toString());
						//------------------------------------
						// 더보기 - 카드 일반
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbCardGnrlAdt.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbCardGnrlChd.toString());
						//------------------------------------
						// 더보기 - 판매룰 닷컴(Adt + Chd + Inf)
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbSiteRuleDtcmAdt.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbSiteRuleDtcmChd.toString());
						if(bCntInf) sbMoreViewInfoFm.append(sbSiteRuleDtcmInf.toString());
						//------------------------------------
						// 더보기 - PF, BEST..닷컴 (Adt + Chd + Inf)
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbAdtDtcmDcAmtInfo.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbChdDtcmDcAmtInfo.toString());
						if(bCntInf) sbMoreViewInfoFm.append(sbInfDtcmDcAmtInfo.toString());
						//------------------------------------
						// 더보기 - TASF 닷컴
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbIsueFeeDtcmAdt.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbIsueFeeDtcmChd.toString());
						if(bCntInf) sbMoreViewInfoFm.append(sbIsueFeeDtcmInf.toString());
						//------------------------------------
						// 더보기 - 특별적립마일리지 닷컴(Adt + Chd)
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbDtcmAdtSpclColtMlgAmt.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbDtcmChdSpclColtMlgAmt.toString());
						//------------------------------------
						// 더보기 - 카드 닷컴
						//------------------------------------
						if(bCntAdt) sbMoreViewInfoFm.append(sbCardDtcmAdt.toString());
						if(bCntChd) sbMoreViewInfoFm.append(sbCardDtcmChd.toString());
						//------------------------------------
						// 더보기 - 대리점컴 - 일반, 닷컴만 row로 출력함(Adt, Chd, Inf는 한줄로 표현함)
						//------------------------------------
						sbTempAgtCmsnGnrlStr = new StringBuilder("");
						sbTempAgtCmsnDtcmStr = new StringBuilder("");

						if(bCntAdt) sbTempAgtCmsnGnrlStr.append(sbAgtCmsnGnrlAdt.toString());	// 대리점컴 - 일반 - ADT
						if(bCntAdt) sbTempAgtCmsnDtcmStr.append(sbAgtCmsnDtcmAdt.toString());	// 대리점컴 - 닷컴 - ADT
						if(bCntChd) {
							// 대리점컴 - 일반 - CHD
							if(StringUtil.isEmpty(sbTempAgtCmsnGnrlStr.toString())) {
								sbTempAgtCmsnGnrlStr.append(sbAgtCmsnGnrlChd.toString());
							} else {
								sbTempAgtCmsnGnrlStr.append("," +sbAgtCmsnGnrlChd.toString());
							}
							// 대리점컴 - 닷컴 - CHD
							if(StringUtil.isEmpty(sbTempAgtCmsnDtcmStr.toString())) {
								sbTempAgtCmsnDtcmStr.append(sbAgtCmsnDtcmChd.toString());
							} else {
								sbTempAgtCmsnDtcmStr.append("," +sbAgtCmsnDtcmChd.toString());
							}
						}
						if(bCntInf) {
							// 대리점컴 - 일반 - INF
							if(StringUtil.isEmpty(sbTempAgtCmsnGnrlStr.toString())) {
								sbTempAgtCmsnGnrlStr.append(sbAgtCmsnGnrlInf.toString());
							} else {
								sbTempAgtCmsnGnrlStr.append("," +sbAgtCmsnGnrlInf.toString());
							}
							// 대리점컴 - 닷컴 - INF
							if(StringUtil.isEmpty(sbTempAgtCmsnDtcmStr.toString())) {
								sbTempAgtCmsnDtcmStr.append(sbAgtCmsnDtcmInf.toString());
							} else {
								sbTempAgtCmsnDtcmStr.append("," +sbAgtCmsnDtcmInf.toString());
							}
						}

						// 대리점컴 - 닷컴..(ADT, CHD, INF 로 표현함)
						sbMoreViewInfoFm.append(sbAgtCmsnGnrlTitle.toString() + sbTempAgtCmsnGnrlStr.toString());
						sbMoreViewInfoFm.append(sbAgtCmsnDtcmTitle.toString() + sbTempAgtCmsnDtcmStr.toString());
						//------------------------------------
						sbMoreViewInfoFm.append("\n");



					}	//인벤토리 타입 제외

					SchAirFareResultVo rsVo = new SchAirFareResultVo();
					// 필터용 컬럼들 [START]
					rsVo.setSplyCd(newSplyCd);				//공급코드
					rsVo.setAlComplex(alComplexChk);		//항공사결합
					rsVo.setTktAlCode(tktAlCode);			//발권항공사코드
					rsVo.setPasnType(pasnType);				//승객구분
					rsVo.setPasnTypes(sbPasnTypes.toString());			//승객구분들
					rsVo.setAdtTamt(adtTamt);				//성인총요금
					rsVo.setChdTamt(chdTamt);				//아동총요금
					rsVo.setInfTamt(infTamt);				//유아총요금
					rsVo.setViaCnt(String.valueOf(nMaxViaCnt));	//경유횟수	- 경유1회이상 있을 경우 직항 제외함.
					rsVo.setMktAlCode(sbMktAlCodes.toString());			//마케팅항공사코드들
                    rsVo.setEqmtName(sbEqmtNames.toString());			//기종명
					rsVo.setMktFltNo(sbMktFltNoInfo.toString());			//항공편명들
					rsVo.setOprAlCode(sbOprAlCodes.toString());			//운항항공사코드들
					rsVo.setCabinType(sbCabinTypes.toString());			//좌석등급
					rsVo.setBookClass(sbBookClassInfo.toString());		//부킹클래스
					rsVo.setFareBasis(sbFareBasisInfo.toString());		//fareBasis
					rsVo.setFareType(sbFareTypes.toString());			//운임유형
					rsVo.setTotTime(totFltTime);			//총비행시간
					rsVo.setStopTime(totStopTime);			//총환승시간
					rsVo.setCabinComplex(cabinComplexChk);	//좌석결합여부
					rsVo.setAcctCode(sbAcctCodes.toString());			//accountCode
					rsVo.setCardPromIds(sbCardPromIds.toString());		//카드프로모션Id
					rsVo.setGnrlEventCode(gnrlEventCds);	//일반이벤트코드들
					rsVo.setDcEventCode(dcEventCd);			//할인이벤트코드
					rsVo.setImdtPayPsblYn(imdtPayPsblYn);	//즉시결제가능여부
					rsVo.setFixFarYn(fixFarYn);				//확정운임여부
					// 필터용 컬럼들 [END]

					//화면 표시용 컬럼들 [START]
					rsVo.setViaInfo(sbViaInfo.toString());
					rsVo.setAlInfo(sbAlInfo.toString());
					rsVo.setFltNoInfo(sbFltNoInfo.toString());
					rsVo.setFltSchdInfo(sbFltSchdInfo.toString());
					rsVo.setFbInfo(sbFbInfo.toString());
					rsVo.setFareTypeInfo(sbFareTypeInfo.toString());
					rsVo.setDtcmAdtDcAmtInfo(sbAdtDtcmDcAmtInfo.toString());
					rsVo.setDtcmChdDcAmtInfo(sbChdDtcmDcAmtInfo.toString());
					rsVo.setDtcmInfDcAmtInfo(sbInfDtcmDcAmtInfo.toString());
					rsVo.setDtcmAdtDcAmtInfoDp("");		// row 6 - 1
					rsVo.setDtcmChdDcAmtInfoDp("");
					rsVo.setDtcmInfDcAmtInfoDp("");
					rsVo.setIsExistCardGnrlInfo(isExistCardGnrlInfo);
					rsVo.setIsExistCardDtcmInfo(isExistCardDtcmInfo);

					rsVo.setGnrlCardPromId(sbGnrlCardPromId.toString());
					rsVo.setGnrlCardPromEventCd(sbGnrlCardPromEventCd.toString());
					rsVo.setGnrlCardNm(sbGnrlCardNm.toString());
					rsVo.setGnrlCardDcInfo(sbGnrlCardDcInfo.toString());
					rsVo.setGnrlCardDcAplAmt(sbGnrlCardDcAplAmt.toString());
					rsVo.setGnrlCardDcTotalAmt(sbGnrlCardDcTotalAmt.toString());
					rsVo.setGnrlCardPromIdDp("");		// row 5 - 1
					rsVo.setGnrlCardPromEventCdDp("");	// row 5 - 2
					rsVo.setGnrlCardNmDp("");			// row 5 - 3
					rsVo.setGnrlCardDcInfoDp("");		// row 5 - 4
					rsVo.setGnrlCardDcAplAmtDp("");		// row 5 - 5
					rsVo.setGnrlCardDcTotalAmtDp("");	// row 6 - 6

					rsVo.setDtcmCardPromId(sbDtcmCardPromId.toString());
					rsVo.setDtcmCardPromEventCd(sbDtcmCardPromEventCd.toString());
					rsVo.setDtcmCardNm(sbDtcmCardNm.toString());
					rsVo.setDtcmCardDcInfo(sbDtcmCardDcInfo.toString());
					rsVo.setDtcmCardDcAplAmt(sbDtcmCardDcAplAmt.toString());
					rsVo.setDtcmCardDcTotalAmt(sbDtcmCardDcTotalAmt.toString());
					rsVo.setDtcmCardPromIdDp("");		// row 7 - 1
					rsVo.setDtcmCardPromEventCdDp("");	// row 7 - 2
					rsVo.setDtcmCardNmDp("");			// row 7 - 3
					rsVo.setDtcmCardDcInfoDp("");		// row 7 - 4
					rsVo.setDtcmCardDcAplAmtDp("");		// row 7 - 5
					rsVo.setDtcmCardDcTotalAmtDp("");	// row 7 - 6

					rsVo.setFareBscInfoView("");		// 더보기 빈값..
					rsVo.setBtnBind1("View JSON..");	// 버튼1 바인딩용
					rsVo.setBtnBind2("Advanced..");		// 버튼2 바인딩용
					rsVo.setFareBscInfo(fareBscInfo);	// row 8
					rsVo.setMoreTxt(moreTxt);

					rsVo.setFareId(sFareId);										// 운임ID
					rsVo.setDefaultInfoFm(sbDefaultInfoFm.toString());				// 기본보기 정보
					rsVo.setMoreViewInfoFm(sbMoreViewInfoFm.toString());			// 더보기 정보
					rsVo.setIsueAirlRuleFarExlsTrgtYn(sIsueAirlRuleFarExlsTrgtYn);	// 노출제어
					rsVo.setMCTRuleFarExlsTrgtYn(sMCTRuleFarExlsTrgtYn);			// MCT노출제어
					rsVo.setAirFarCombYn(sAirFarCombYn);				//편도결합여부

					//화면 표시용 컬럼들 [END]

					fareList.add(rsVo);

					}


			}	//공급코드 존재여부확인	
		}	//운임리스트
		
		// ########################## 필터리스트 setting #######################
		String splyCds = "";
		
		if(!CollectionUtils.isEmpty(fareList)) {
			List<String> splyCdList = new ArrayList(splyCdMap.keySet());
			splyCds = String.join(",", splyCdList);
			
			Iterator<String> fltrIt = fareFltrMap.keySet().iterator();
			while(fltrIt.hasNext()){
				String keyNm = fltrIt.next();
				
				if(keyNm.indexOf(FltrType.ADT_T_AMT.getFltrFld()) != -1){
					this.setRangeFilter(fareFltrList, fareFltrMap.get(keyNm), FltrType.ADT_T_AMT);
				}else if(keyNm.indexOf(FltrType.TOT_TIME.getFltrFld()) != -1){
					this.setRangeFilter(fareFltrList, fareFltrMap.get(keyNm), FltrType.TOT_TIME);
				}else if(keyNm.indexOf(FltrType.STOP_TIME.getFltrFld()) != -1){
					this.setRangeFilter(fareFltrList, fareFltrMap.get(keyNm), FltrType.STOP_TIME);
				}else {
					fareFltrList.add(fareFltrMap.get(keyNm));
				}
			}
		}
		
		//OutVo 운임조회리스트 setting
		schAirFareCbcOutVo.setFareList(fareList);

		//OutVo 필터리스트 setting
		schAirFareCbcOutVo.setFareFltrList(fareFltrList);
		schAirFareCbcOutVo.setSplyCds(splyCds);
		schAirFareCbcOutVo.setJsonRes(resJsonStr);
		schAirFareCbcOutVo.setFarLstCnt(farLstCnt);	// 화면에서 운임 건수 없을 경우 Alert 띄워주기 위해 추가

		//------------------------------
		// Set Response(JSON) 
		//------------------------------
		schAirFareCbcOutVo.setJsonBody(ds_jsonBody);
		schAirFareCbcOutVo.setJsonBizCom(ds_jsonBizCom);
		schAirFareCbcOutVo.setJsonFarLst(ds_jsonFarLst);
		schAirFareCbcOutVo.setJsonFarPrcLst(ds_jsonFarPrcLst);
		schAirFareCbcOutVo.setJsonFeeDtlLst(ds_jsonFeeDtl);
		schAirFareCbcOutVo.setJsonItnrLst(ds_jsonItnrLst);
		schAirFareCbcOutVo.setJsonFltLst(ds_jsonFltLst);
		schAirFareCbcOutVo.setJsonFltPrcLst(ds_jsonFltPrcLst);
		schAirFareCbcOutVo.setJsonFreeBaggLst(ds_jsonFreeBaggLst);
		//------------------------------
		
		return schAirFareCbcOutVo;
	}
	/**
	 * 판매룰(마크업/할인) + 할인이벤트 텍스트를 조립한다.
	 * farVoLst(편도결합 시)와 farLstNode(비결합 시)는 필드명이 동일하므로 fareNode 하나로 통일해서 달수 없이 재사용한다.
	 * 원본(makeResponse 내 편도결합/비결합 분기) 로직은 무변경, 참조하는 JsonNode만 외부에서 주입받는다.
	 */
	private SiteRuleTexts buildSiteRuleTexts(JsonNode fareNode) {
		//############## 사이트 룰 할인 정보 ###################################################
		StringBuilder sbSiteRuleGnrlAdt = new StringBuilder("\n일반 판매룰 ADT ");
		StringBuilder sbSiteRuleGnrlChd = new StringBuilder("\n일반 판매룰 CHD ");
		StringBuilder sbSiteRuleGnrlInf = new StringBuilder("\n일반 판매룰 INF ");
		StringBuilder sbSiteRuleDtcmAdt = new StringBuilder("\n닷컴 판매룰 ADT ");
		StringBuilder sbSiteRuleDtcmChd = new StringBuilder("\n닷컴 판매룰 CHD ");
		StringBuilder sbSiteRuleDtcmInf = new StringBuilder("\n닷컴 판매룰 INF ");

		//--------------------------------------
		// 판매룰 마크업금액
		//--------------------------------------
		String mkupSaleRulebsId      = StringUtil.nullConvert(fareNode.path("mkupSaleRulebsId"     ).textValue());
		String mkupSaleRulebsCmsnSeq = StringUtil.nullConvert(fareNode.path("mkupSaleRulebsCmsnSeq").toString());
		if(StringUtil.isEmpty(mkupSaleRulebsId)) {
			sbSiteRuleGnrlAdt.append("M - ");
			sbSiteRuleGnrlChd.append("M - ");
			sbSiteRuleGnrlInf.append("M - ");
			sbSiteRuleDtcmAdt.append("M - ");
			sbSiteRuleDtcmChd.append("M - ");
			sbSiteRuleDtcmInf.append("M - ");
		}else {
			String gnrlSaleRulebsMkupAdtTrf = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsMkupAdtTrf").asText());
			String gnrlSaleRulebsMkupChdTrf = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsMkupChdTrf").asText());
			String gnrlSaleRulebsMkupInfTrf = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsMkupInfTrf").asText());
			String dtcmSaleRulebsMkupAdtTrf = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsMkupAdtTrf").asText());
			String dtcmSaleRulebsMkupChdTrf = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsMkupChdTrf").asText());
			String dtcmSaleRulebsMkupInfTrf = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsMkupInfTrf").asText());
			String gnrlSaleRulebsMkupAdtAmt = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsMkupAdtAmt").asText());
			String gnrlSaleRulebsMkupChdAmt = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsMkupChdAmt").asText());
			String gnrlSaleRulebsMkupInfAmt = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsMkupInfAmt").asText());
			String dtcmSaleRulebsMkupAdtAmt = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsMkupAdtAmt").asText());
			String dtcmSaleRulebsMkupChdAmt = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsMkupChdAmt").asText());
			String dtcmSaleRulebsMkupInfAmt = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsMkupInfAmt").asText());

			// 할인판매룰커미션적용방식코드 : R - 정률, A - 정액
			String mkupSaleRulebsCmsnAplMthdCd = StringUtil.nullConvert(fareNode.path("mkupSaleRulebsCmsnAplMthdCd").asText());
			if("R".equals(mkupSaleRulebsCmsnAplMthdCd)) {
				//------------------------
				// 정률
				//------------------------
				sbSiteRuleGnrlAdt.append("M R " + gnrlSaleRulebsMkupAdtTrf+"% / " + NumberUtil.formatNumber(gnrlSaleRulebsMkupAdtAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlChd.append("M R " + gnrlSaleRulebsMkupChdTrf+"% / " + NumberUtil.formatNumber(gnrlSaleRulebsMkupChdAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlInf.append("M R " + gnrlSaleRulebsMkupInfTrf+"% / " + NumberUtil.formatNumber(gnrlSaleRulebsMkupInfAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmAdt.append("M R " + dtcmSaleRulebsMkupAdtTrf+"% / " + NumberUtil.formatNumber(dtcmSaleRulebsMkupAdtAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmChd.append("M R " + dtcmSaleRulebsMkupChdTrf+"% / " + NumberUtil.formatNumber(dtcmSaleRulebsMkupChdAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmInf.append("M R " + dtcmSaleRulebsMkupInfTrf+"% / " + NumberUtil.formatNumber(dtcmSaleRulebsMkupInfAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
			} else if("A".equals(mkupSaleRulebsCmsnAplMthdCd)) {
				//------------------------
				// 정액
				//------------------------
				sbSiteRuleGnrlAdt.append("M A / " + NumberUtil.formatNumber(gnrlSaleRulebsMkupAdtAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlChd.append("M A / " + NumberUtil.formatNumber(gnrlSaleRulebsMkupChdAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlInf.append("M A / " + NumberUtil.formatNumber(gnrlSaleRulebsMkupInfAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmAdt.append("M A / " + NumberUtil.formatNumber(dtcmSaleRulebsMkupAdtAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmChd.append("M A / " + NumberUtil.formatNumber(dtcmSaleRulebsMkupChdAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmInf.append("M A / " + NumberUtil.formatNumber(dtcmSaleRulebsMkupInfAmt, "#,###,###")+" (No."+mkupSaleRulebsId+" - "+mkupSaleRulebsCmsnSeq+") ");
			}
		}
		//--------------------------------------
		// 판매룰 할인금액
		//--------------------------------------
		String dcSaleRulebsId      = StringUtil.nullConvert(fareNode.path("dcSaleRulebsId"     ).textValue());
		String dcSaleRulebsCmsnSeq = StringUtil.nullConvert(fareNode.path("dcSaleRulebsCmsnSeq").toString());
		if(StringUtil.isEmpty(dcSaleRulebsId)) {
			sbSiteRuleGnrlAdt.append(" D - ");
			sbSiteRuleGnrlChd.append(" D - ");
			sbSiteRuleGnrlInf.append(" D - ");
			sbSiteRuleDtcmAdt.append(" D - ");
			sbSiteRuleDtcmChd.append(" D - ");
			sbSiteRuleDtcmInf.append(" D - ");
		}else {
			String gnrlSaleRulebsDcAdtTrf = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsDcAdtTrf").asText());
			String gnrlSaleRulebsDcChdTrf = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsDcChdTrf").asText());
			String gnrlSaleRulebsDcInfTrf = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsDcInfTrf").asText());
			String dtcmSaleRulebsDcAdtTrf = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsDcAdtTrf").asText());
			String dtcmSaleRulebsDcChdTrf = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsDcChdTrf").asText());
			String dtcmSaleRulebsDcInfTrf = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsDcInfTrf").asText());
			String gnrlSaleRulebsDcAdtAmt = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsDcAdtAmt").asText());
			String gnrlSaleRulebsDcChdAmt = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsDcChdAmt").asText());
			String gnrlSaleRulebsDcInfAmt = StringUtil.nullConvert(fareNode.path("gnrlSaleRulebsDcInfAmt").asText());
			String dtcmSaleRulebsDcAdtAmt = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsDcAdtAmt").asText());
			String dtcmSaleRulebsDcChdAmt = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsDcChdAmt").asText());
			String dtcmSaleRulebsDcInfAmt = StringUtil.nullConvert(fareNode.path("dtcmSaleRulebsDcInfAmt").asText());

			// 할인판매룰커미션적용방식코드 : R - 정률, A - 정액
			String dcSaleRulebsCmsnAplMthdCd = StringUtil.nullConvert(fareNode.path("dcSaleRulebsCmsnAplMthdCd").asText());
			if("R".equals(dcSaleRulebsCmsnAplMthdCd)) {
				//------------------------
				// 정률
				//------------------------
				sbSiteRuleGnrlAdt.append(" D R " + gnrlSaleRulebsDcAdtTrf+"% / " + NumberUtil.formatNumber(gnrlSaleRulebsDcAdtAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlChd.append(" D R " + gnrlSaleRulebsDcChdTrf+"% / " + NumberUtil.formatNumber(gnrlSaleRulebsDcChdAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlInf.append(" D R " + gnrlSaleRulebsDcInfTrf+"% / " + NumberUtil.formatNumber(gnrlSaleRulebsDcInfAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmAdt.append(" D R " + dtcmSaleRulebsDcAdtTrf+"% / " + NumberUtil.formatNumber(dtcmSaleRulebsDcAdtAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmChd.append(" D R " + dtcmSaleRulebsDcChdTrf+"% / " + NumberUtil.formatNumber(dtcmSaleRulebsDcChdAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmInf.append(" D R " + dtcmSaleRulebsDcInfTrf+"% / " + NumberUtil.formatNumber(dtcmSaleRulebsDcInfAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
			} else if("A".equals(dcSaleRulebsCmsnAplMthdCd)) {
				//------------------------
				// 정액
				//------------------------
				sbSiteRuleGnrlAdt.append(" D A / " + NumberUtil.formatNumber(gnrlSaleRulebsDcAdtAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlChd.append(" D A / " + NumberUtil.formatNumber(gnrlSaleRulebsDcChdAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleGnrlInf.append(" D A / " + NumberUtil.formatNumber(gnrlSaleRulebsDcInfAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmAdt.append(" D A / " + NumberUtil.formatNumber(dtcmSaleRulebsDcAdtAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmChd.append(" D A / " + NumberUtil.formatNumber(dtcmSaleRulebsDcChdAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
				sbSiteRuleDtcmInf.append(" D A / " + NumberUtil.formatNumber(dtcmSaleRulebsDcInfAmt, "#,###,###")+" (No."+dcSaleRulebsId+" - "+dcSaleRulebsCmsnSeq+") ");
			}
		}
		//--------------------------------------
		// 할인이벤트 정보
		//--------------------------------------
		String dcEventCd = StringUtil.nullConvert(fareNode.path("dcEventCd").textValue());	// 할인이벤트코드
		String dcEventNm = StringUtil.nullConvert(fareNode.path("dcEventNm").textValue());	// 할인이벤트명
		String dcEventId = StringUtil.nullConvert(fareNode.path("eventId").textValue());					//eventId

		if(StringUtil.isEmpty(dcEventCd)){
			sbSiteRuleGnrlAdt.append(" 할인이벤트 ADT - ");
			sbSiteRuleGnrlChd.append(" 할인이벤트 CHD - ");
			sbSiteRuleDtcmAdt.append(" 할인이벤트 ADT - ");
			sbSiteRuleDtcmChd.append(" 할인이벤트 CHD - ");
		}else {
			String dcEventAdtTrf     = StringUtil.nullConvert(fareNode.path("dcEventAdtTrf"    ).asText());	// 할인이벤트성인요율
			String dcEventChdTrf     = StringUtil.nullConvert(fareNode.path("dcEventChdTrf"    ).asText());	// 할인이벤트아동요율
			String gnrlDcEventAdtAmt = StringUtil.nullConvert(fareNode.path("gnrlDcEventAdtAmt").asText());	// 비회원할인이벤트성인할인금액
			String gnrlDcEventChdAmt = StringUtil.nullConvert(fareNode.path("gnrlDcEventChdAmt").asText());	// 비회원할인이벤트아동할인금액
			String dtcmDcEventAdtAmt = StringUtil.nullConvert(fareNode.path("dtcmDcEventAdtAmt").asText());	// 회원할인이벤트성인할인금액
			String dtcmDcEventChdAmt = StringUtil.nullConvert(fareNode.path("dtcmDcEventChdAmt").asText());	// 회원할인이벤트아동할인금액

			// 할인이벤트방식코드 : R - 정률, A - 정액
			String dcEventCmsnAplMthdCd = StringUtil.nullConvert(fareNode.path("dcEventCmsnAplMthdCd").textValue());	// 할인이벤트방식코드
			if("R".equals(dcEventCmsnAplMthdCd)) {
				//------------------------
				// 정률
				//------------------------
				sbSiteRuleGnrlAdt.append(" 할인이벤트 ADT R " + dcEventAdtTrf+"% / " + NumberUtil.formatNumber(gnrlDcEventAdtAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
				sbSiteRuleGnrlChd.append(" 할인이벤트 CHD R " + dcEventChdTrf+"% / " + NumberUtil.formatNumber(gnrlDcEventChdAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
				sbSiteRuleDtcmAdt.append(" 할인이벤트 ADT R " + dcEventAdtTrf+"% / " + NumberUtil.formatNumber(dtcmDcEventAdtAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
				sbSiteRuleDtcmChd.append(" 할인이벤트 CHD R " + dcEventChdTrf+"% / " + NumberUtil.formatNumber(dtcmDcEventChdAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
			} else if("A".equals(dcEventCmsnAplMthdCd)) {
				//------------------------
				// 정액
				//------------------------
				sbSiteRuleGnrlAdt.append(" 할인이벤트 ADT A / " + NumberUtil.formatNumber(gnrlDcEventAdtAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
				sbSiteRuleGnrlChd.append(" 할인이벤트 CHD A / " + NumberUtil.formatNumber(gnrlDcEventChdAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
				sbSiteRuleDtcmAdt.append(" 할인이벤트 ADT A / " + NumberUtil.formatNumber(dtcmDcEventAdtAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
				sbSiteRuleDtcmChd.append(" 할인이벤트 CHD A / " + NumberUtil.formatNumber(dtcmDcEventChdAmt, "#,###,###")+" (No."+ dcEventId + " - " +dcEventCd+" "+dcEventNm+") ");
			}
		}


		return new SiteRuleTexts(sbSiteRuleGnrlAdt, sbSiteRuleGnrlChd, sbSiteRuleGnrlInf,
			sbSiteRuleDtcmAdt, sbSiteRuleDtcmChd, sbSiteRuleDtcmInf, dcEventCd, dcEventNm);
	}

	/** buildSiteRuleTexts()의 결과를 담는 불변 보유체. */
	private static final class SiteRuleTexts {
		private final StringBuilder sbSiteRuleGnrlAdt;
		private final StringBuilder sbSiteRuleGnrlChd;
		private final StringBuilder sbSiteRuleGnrlInf;
		private final StringBuilder sbSiteRuleDtcmAdt;
		private final StringBuilder sbSiteRuleDtcmChd;
		private final StringBuilder sbSiteRuleDtcmInf;
		private final String dcEventCd;
		private final String dcEventNm;

		private SiteRuleTexts(StringBuilder sbSiteRuleGnrlAdt, StringBuilder sbSiteRuleGnrlChd, StringBuilder sbSiteRuleGnrlInf,
				StringBuilder sbSiteRuleDtcmAdt, StringBuilder sbSiteRuleDtcmChd, StringBuilder sbSiteRuleDtcmInf,
				String dcEventCd, String dcEventNm) {
			this.sbSiteRuleGnrlAdt = sbSiteRuleGnrlAdt;
			this.sbSiteRuleGnrlChd = sbSiteRuleGnrlChd;
			this.sbSiteRuleGnrlInf = sbSiteRuleGnrlInf;
			this.sbSiteRuleDtcmAdt = sbSiteRuleDtcmAdt;
			this.sbSiteRuleDtcmChd = sbSiteRuleDtcmChd;
			this.sbSiteRuleDtcmInf = sbSiteRuleDtcmInf;
			this.dcEventCd = dcEventCd;
			this.dcEventNm = dcEventNm;
		}
	}
	/**
	 * 운임 계산용 금액(Q/FUEL/TAX/TASF 등) 및 포맷 문자열을 계산한다.
	 * farVoLst(편도결합)/farLstNode(비결합)는 필드명이 동일하므로 fareNode 하나로 통일.
	 * 원본 로직 무변경.
	 */
	private FeeAmountTexts computeFeeAmountTexts(JsonNode fareNode) {
		long adtQchrgAmtL       = fareNode.path("adtQchrgAmt"      ).asLong(0);	//계산용-성인Q차지금액
		long chdQchrgAmtL       = fareNode.path("chdQchrgAmt"      ).asLong(0);	//계산용-아동Q차지금액
		long infQchrgAmtL       = fareNode.path("infQchrgAmt"      ).asLong(0);	//계산용-유아Q차지금액
		long adtTaxAmtL         = fareNode.path("adtTaxAmt"        ).asLong(0);	//계산용-성인세금금액
		long chdTaxAmtL         = fareNode.path("chdTaxAmt"        ).asLong(0);	//계산용-아동세금금액
		long infTaxAmtL         = fareNode.path("infTaxAmt"        ).asLong(0);	//계산용-유아세금금액
		long gnrlAdtIsueFeeAmtL = fareNode.path("gnrlAdtIsueFeeAmt").asLong(0);	//계산용-성인발권수수료금액
		long gnrlChdIsueFeeAmtL = fareNode.path("gnrlChdIsueFeeAmt").asLong(0);	//계산용-아동발권수수료금액
		long gnrlInfIsueFeeAmtL = fareNode.path("gnrlInfIsueFeeAmt").asLong(0);	//계산용-유아발권수수료금액
		long dtcmAdtIsueFeeAmtL = fareNode.path("dtcmAdtIsueFeeAmt").asLong(0);	//계산용-닷컴성인발권수수료금액
		long dtcmChdIsueFeeAmtL = fareNode.path("dtcmChdIsueFeeAmt").asLong(0);	//계산용-닷컴아동발권수수료금액
		long dtcmInfIsueFeeAmtL = fareNode.path("dtcmInfIsueFeeAmt").asLong(0);	//계산용-닷컴유아발권수수료금액
		long adtFuelExchgAmtL   = fareNode.path("adtFuelExchgAmt"  ).asLong(0);	//계산용-성인유류할증금액
		long chdFuelExchgAmtL   = fareNode.path("chdFuelExchgAmt"  ).asLong(0);	//계산용-아동유류할증금액
		long infFuelExchgAmtL   = fareNode.path("infFuelExchgAmt"  ).asLong(0);	//계산용-유아유류할증금액
		long gnrlAdtEtcAmtL = adtQchrgAmtL + adtTaxAmtL + gnrlAdtIsueFeeAmtL + adtFuelExchgAmtL;	//계산용-기타금액합계 - 일반
		long gnrlChdEtcAmtL = chdQchrgAmtL + chdTaxAmtL + gnrlChdIsueFeeAmtL + chdFuelExchgAmtL;	//계산용-기타금액합계 - 일반
		long gnrlInfEtcAmtL = infQchrgAmtL + infTaxAmtL + gnrlInfIsueFeeAmtL + infFuelExchgAmtL;	//계산용-기타금액합계 - 일반
		long dtcmAdtEtcAmtL = adtQchrgAmtL + adtTaxAmtL + dtcmAdtIsueFeeAmtL + adtFuelExchgAmtL;	//계산용-기타금액합계 - 닷컴
		long dtcmChdEtcAmtL = chdQchrgAmtL + chdTaxAmtL + dtcmChdIsueFeeAmtL + chdFuelExchgAmtL;	//계산용-기타금액합계 - 닷컴
		long dtcmInfEtcAmtL = infQchrgAmtL + infTaxAmtL + dtcmInfIsueFeeAmtL + infFuelExchgAmtL;	//계산용-기타금액합계 - 닷컴

		String adtQchrgAmtStr       = "Q "           + NumberUtil.formatNumber(String.valueOf(adtQchrgAmtL    ), "#,###,###");
		String chdQchrgAmtStr       = "Q "           + NumberUtil.formatNumber(String.valueOf(chdQchrgAmtL    ), "#,###,###");
		String infQchrgAmtStr       = "Q "           + NumberUtil.formatNumber(String.valueOf(infQchrgAmtL    ), "#,###,###");
		String adtFuelExchgAmtStr   = " FUEL "       + NumberUtil.formatNumber(String.valueOf(adtFuelExchgAmtL), "#,###,###");
		String chdFuelExchgAmtStr   = " FUEL "       + NumberUtil.formatNumber(String.valueOf(chdFuelExchgAmtL), "#,###,###");
		String infFuelExchgAmtStr   = " FUEL "       + NumberUtil.formatNumber(String.valueOf(infFuelExchgAmtL), "#,###,###");
		String adtTaxAmtStr         = " TAX "        + NumberUtil.formatNumber(String.valueOf(adtTaxAmtL      ), "#,###,###");
		String chdTaxAmtStr         = " TAX "        + NumberUtil.formatNumber(String.valueOf(chdTaxAmtL      ), "#,###,###");
		String infTaxAmtStr         = " TAX "        + NumberUtil.formatNumber(String.valueOf(infTaxAmtL      ), "#,###,###");
		String gnrlAdtIsueFeeAmtStr = " TASF "       + NumberUtil.formatNumber(String.valueOf(gnrlAdtIsueFeeAmtL  ), "#,###,###");
		String gnrlChdIsueFeeAmtStr = " TASF "       + NumberUtil.formatNumber(String.valueOf(gnrlChdIsueFeeAmtL  ), "#,###,###");
		String gnrlInfIsueFeeAmtStr = " TASF "       + NumberUtil.formatNumber(String.valueOf(gnrlInfIsueFeeAmtL  ), "#,###,###");
		String dtcmAdtIsueFeeAmtStr = " TASF "       + NumberUtil.formatNumber(String.valueOf(dtcmAdtIsueFeeAmtL  ), "#,###,###");
		String dtcmChdIsueFeeAmtStr = " TASF "       + NumberUtil.formatNumber(String.valueOf(dtcmChdIsueFeeAmtL  ), "#,###,###");
		String dtcmInfIsueFeeAmtStr = " TASF "       + NumberUtil.formatNumber(String.valueOf(dtcmInfIsueFeeAmtL  ), "#,###,###");


		return new FeeAmountTexts(gnrlAdtEtcAmtL, gnrlChdEtcAmtL, gnrlInfEtcAmtL, dtcmAdtEtcAmtL, dtcmChdEtcAmtL, dtcmInfEtcAmtL, adtQchrgAmtStr, chdQchrgAmtStr, infQchrgAmtStr, adtFuelExchgAmtStr, chdFuelExchgAmtStr, infFuelExchgAmtStr, adtTaxAmtStr, chdTaxAmtStr, infTaxAmtStr, gnrlAdtIsueFeeAmtStr, gnrlChdIsueFeeAmtStr, gnrlInfIsueFeeAmtStr, dtcmAdtIsueFeeAmtStr, dtcmChdIsueFeeAmtStr, dtcmInfIsueFeeAmtStr);
	}

	/** computeFeeAmountTexts()의 결과를 담는 불변 보유체. */
	private static final class FeeAmountTexts {
		private final long gnrlAdtEtcAmtL;
		private final long gnrlChdEtcAmtL;
		private final long gnrlInfEtcAmtL;
		private final long dtcmAdtEtcAmtL;
		private final long dtcmChdEtcAmtL;
		private final long dtcmInfEtcAmtL;
		private final String adtQchrgAmtStr;
		private final String chdQchrgAmtStr;
		private final String infQchrgAmtStr;
		private final String adtFuelExchgAmtStr;
		private final String chdFuelExchgAmtStr;
		private final String infFuelExchgAmtStr;
		private final String adtTaxAmtStr;
		private final String chdTaxAmtStr;
		private final String infTaxAmtStr;
		private final String gnrlAdtIsueFeeAmtStr;
		private final String gnrlChdIsueFeeAmtStr;
		private final String gnrlInfIsueFeeAmtStr;
		private final String dtcmAdtIsueFeeAmtStr;
		private final String dtcmChdIsueFeeAmtStr;
		private final String dtcmInfIsueFeeAmtStr;

		private FeeAmountTexts(long gnrlAdtEtcAmtL, long gnrlChdEtcAmtL, long gnrlInfEtcAmtL, long dtcmAdtEtcAmtL, long dtcmChdEtcAmtL, long dtcmInfEtcAmtL, String adtQchrgAmtStr, String chdQchrgAmtStr, String infQchrgAmtStr, String adtFuelExchgAmtStr, String chdFuelExchgAmtStr, String infFuelExchgAmtStr, String adtTaxAmtStr, String chdTaxAmtStr, String infTaxAmtStr, String gnrlAdtIsueFeeAmtStr, String gnrlChdIsueFeeAmtStr, String gnrlInfIsueFeeAmtStr, String dtcmAdtIsueFeeAmtStr, String dtcmChdIsueFeeAmtStr, String dtcmInfIsueFeeAmtStr) {
			this.gnrlAdtEtcAmtL = gnrlAdtEtcAmtL;
			this.gnrlChdEtcAmtL = gnrlChdEtcAmtL;
			this.gnrlInfEtcAmtL = gnrlInfEtcAmtL;
			this.dtcmAdtEtcAmtL = dtcmAdtEtcAmtL;
			this.dtcmChdEtcAmtL = dtcmChdEtcAmtL;
			this.dtcmInfEtcAmtL = dtcmInfEtcAmtL;
			this.adtQchrgAmtStr = adtQchrgAmtStr;
			this.chdQchrgAmtStr = chdQchrgAmtStr;
			this.infQchrgAmtStr = infQchrgAmtStr;
			this.adtFuelExchgAmtStr = adtFuelExchgAmtStr;
			this.chdFuelExchgAmtStr = chdFuelExchgAmtStr;
			this.infFuelExchgAmtStr = infFuelExchgAmtStr;
			this.adtTaxAmtStr = adtTaxAmtStr;
			this.chdTaxAmtStr = chdTaxAmtStr;
			this.infTaxAmtStr = infTaxAmtStr;
			this.gnrlAdtIsueFeeAmtStr = gnrlAdtIsueFeeAmtStr;
			this.gnrlChdIsueFeeAmtStr = gnrlChdIsueFeeAmtStr;
			this.gnrlInfIsueFeeAmtStr = gnrlInfIsueFeeAmtStr;
			this.dtcmAdtIsueFeeAmtStr = dtcmAdtIsueFeeAmtStr;
			this.dtcmChdIsueFeeAmtStr = dtcmChdIsueFeeAmtStr;
			this.dtcmInfIsueFeeAmtStr = dtcmInfIsueFeeAmtStr;
		}
	}
	/**
	 * 일반/닷컴 할인적용 요금 텍스트와 필터용 최종금액(adt/chd/infTamt)을 계산한다.
	 * farVoLst(편도결합)/farLstNode(비결합)는 필드명이 동일하므로 fareNode 하나로 통일. 원본 로직 무변경.
	 */
	private GeneralDiscountFareTexts computeGeneralDiscountFareTexts(JsonNode fareNode, FeeAmountTexts feeAmountTexts) {
		//#################### 일반할인요금정보 ###################################
		StringBuilder sbAdtGnrlDcAmtInfoTemp = new StringBuilder("");
		StringBuilder sbChdGnrlDcAmtInfoTemp = new StringBuilder("");
		StringBuilder sbInfGnrlDcAmtInfoTemp = new StringBuilder("");
		StringBuilder sbAdtDtcmDcAmtInfoTemp = new StringBuilder("");
		StringBuilder sbChdDtcmDcAmtInfoTemp = new StringBuilder("");
		StringBuilder sbInfDtcmDcAmtInfoTemp = new StringBuilder("");

		long gnrlAdtDcAplSaleAmtL = fareNode.path("gnrlAdtDcAplSaleAmt").asLong(0);	//계산용-일반성인할인적용판매금액
		long gnrlChdDcAplSaleAmtL = fareNode.path("gnrlChdDcAplSaleAmt").asLong(0);	//계산용-일반성인할인적용판매금액
		long gnrlInfDcAplSaleAmtL = fareNode.path("gnrlInfDcAplSaleAmt").asLong(0);	//계산용-일반성인할인적용판매금액
		long dtcmAdtDcAplSaleAmtL = fareNode.path("dtcmAdtDcAplSaleAmt").asLong(0);	//계산용-닷컴성인할인적용판매금액
		long dtcmChdDcAplSaleAmtL = fareNode.path("dtcmChdDcAplSaleAmt").asLong(0);	//계산용-닷컴성인할인적용판매금액
		long dtcmInfDcAplSaleAmtL = fareNode.path("dtcmInfDcAplSaleAmt").asLong(0);	//계산용-닷컴성인할인적용판매금액

		long gnrlDcAdtTotalAmt = gnrlAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;		//일반할인 최종요금
		long gnrlDcChdTotalAmt = gnrlChdDcAplSaleAmtL + feeAmountTexts.gnrlChdEtcAmtL;		//일반할인 최종요금
		long gnrlDcInfTotalAmt = gnrlInfDcAplSaleAmtL + feeAmountTexts.gnrlInfEtcAmtL;		//일반할인 최종요금
		long dtcmAdtDcTotalAmt = dtcmAdtDcAplSaleAmtL + feeAmountTexts.dtcmAdtEtcAmtL;		//닷컴판매룰할인 최종요금
		long dtcmChdDcTotalAmt = dtcmChdDcAplSaleAmtL + feeAmountTexts.dtcmChdEtcAmtL;		//닷컴판매룰할인 최종요금
		long dtcmInfDcTotalAmt = dtcmInfDcAplSaleAmtL + feeAmountTexts.dtcmInfEtcAmtL;		//닷컴판매룰할인 최종요금

		// 20200305 : SocketTimeoutException 회피를 위한 성능개선(String 객체 StringBuilder 변경)
		sbAdtGnrlDcAmtInfoTemp.append(NumberUtil.formatNumber(String.valueOf(gnrlAdtDcAplSaleAmtL), "#,###,###") + " (" + feeAmountTexts.adtQchrgAmtStr + feeAmountTexts.adtFuelExchgAmtStr + feeAmountTexts.adtTaxAmtStr + feeAmountTexts.gnrlAdtIsueFeeAmtStr + ")" + " Total " + NumberUtil.formatNumber(String.valueOf(gnrlDcAdtTotalAmt), "#,###,###"));
		sbChdGnrlDcAmtInfoTemp.append(NumberUtil.formatNumber(String.valueOf(gnrlChdDcAplSaleAmtL), "#,###,###") + " (" + feeAmountTexts.chdQchrgAmtStr + feeAmountTexts.chdFuelExchgAmtStr + feeAmountTexts.chdTaxAmtStr + feeAmountTexts.gnrlChdIsueFeeAmtStr + ")" + " Total " + NumberUtil.formatNumber(String.valueOf(gnrlDcChdTotalAmt), "#,###,###"));
		sbInfGnrlDcAmtInfoTemp.append(NumberUtil.formatNumber(String.valueOf(gnrlInfDcAplSaleAmtL), "#,###,###") + " (" + feeAmountTexts.infQchrgAmtStr + feeAmountTexts.infFuelExchgAmtStr + feeAmountTexts.infTaxAmtStr + feeAmountTexts.gnrlInfIsueFeeAmtStr + ")" + " Total " + NumberUtil.formatNumber(String.valueOf(gnrlDcInfTotalAmt), "#,###,###"));
		sbAdtDtcmDcAmtInfoTemp.append(NumberUtil.formatNumber(String.valueOf(dtcmAdtDcAplSaleAmtL), "#,###,###") + " (" + feeAmountTexts.adtQchrgAmtStr + feeAmountTexts.adtFuelExchgAmtStr + feeAmountTexts.adtTaxAmtStr + feeAmountTexts.dtcmAdtIsueFeeAmtStr + ")" + " Total " + NumberUtil.formatNumber(String.valueOf(dtcmAdtDcTotalAmt), "#,###,###"));
		sbChdDtcmDcAmtInfoTemp.append(NumberUtil.formatNumber(String.valueOf(dtcmChdDcAplSaleAmtL), "#,###,###") + " (" + feeAmountTexts.chdQchrgAmtStr + feeAmountTexts.chdFuelExchgAmtStr + feeAmountTexts.chdTaxAmtStr + feeAmountTexts.dtcmChdIsueFeeAmtStr + ")" + " Total " + NumberUtil.formatNumber(String.valueOf(dtcmChdDcTotalAmt), "#,###,###"));
		sbInfDtcmDcAmtInfoTemp.append(NumberUtil.formatNumber(String.valueOf(dtcmInfDcAplSaleAmtL), "#,###,###") + " (" + feeAmountTexts.infQchrgAmtStr + feeAmountTexts.infFuelExchgAmtStr + feeAmountTexts.infTaxAmtStr + feeAmountTexts.dtcmInfIsueFeeAmtStr + ")" + " Total " + NumberUtil.formatNumber(String.valueOf(dtcmInfDcTotalAmt), "#,###,###"));

		//###################### 닷컴할인요금정보 ####################################

		//필터링에 사용할 최종금액 설정


		return new GeneralDiscountFareTexts(sbAdtGnrlDcAmtInfoTemp, sbChdGnrlDcAmtInfoTemp, sbInfGnrlDcAmtInfoTemp,
			sbAdtDtcmDcAmtInfoTemp, sbChdDtcmDcAmtInfoTemp, sbInfDtcmDcAmtInfoTemp,
			gnrlDcAdtTotalAmt, gnrlDcChdTotalAmt, gnrlDcInfTotalAmt);
	}

	/** computeGeneralDiscountFareTexts()의 결과를 담는 불변 보유체. */
	private static final class GeneralDiscountFareTexts {
		private final StringBuilder sbAdtGnrlDcAmtInfoTemp;
		private final StringBuilder sbChdGnrlDcAmtInfoTemp;
		private final StringBuilder sbInfGnrlDcAmtInfoTemp;
		private final StringBuilder sbAdtDtcmDcAmtInfoTemp;
		private final StringBuilder sbChdDtcmDcAmtInfoTemp;
		private final StringBuilder sbInfDtcmDcAmtInfoTemp;
		private final long adtTamt;
		private final long chdTamt;
		private final long infTamt;

		private GeneralDiscountFareTexts(StringBuilder sbAdtGnrlDcAmtInfoTemp, StringBuilder sbChdGnrlDcAmtInfoTemp, StringBuilder sbInfGnrlDcAmtInfoTemp, StringBuilder sbAdtDtcmDcAmtInfoTemp, StringBuilder sbChdDtcmDcAmtInfoTemp, StringBuilder sbInfDtcmDcAmtInfoTemp, long adtTamt, long chdTamt, long infTamt) {
			this.sbAdtGnrlDcAmtInfoTemp = sbAdtGnrlDcAmtInfoTemp;
			this.sbChdGnrlDcAmtInfoTemp = sbChdGnrlDcAmtInfoTemp;
			this.sbInfGnrlDcAmtInfoTemp = sbInfGnrlDcAmtInfoTemp;
			this.sbAdtDtcmDcAmtInfoTemp = sbAdtDtcmDcAmtInfoTemp;
			this.sbChdDtcmDcAmtInfoTemp = sbChdDtcmDcAmtInfoTemp;
			this.sbInfDtcmDcAmtInfoTemp = sbInfDtcmDcAmtInfoTemp;
			this.adtTamt = adtTamt;
			this.chdTamt = chdTamt;
			this.infTamt = infTamt;
		}
	}
	/**
	 * 카드프로모션(일반/닷컴, 스마트/골드 등급 포함) 텍스트를 조립하고 카드프로모션 필터를 설정한다.
	 * farVoLst(편도결합)/farLstNode(비결합)는 필드명이 동일하므로 fareNode 하나로 통일. 원본 로직 무변경.
	 * sbGnrlCardProm, sbDtcmCardProm, sbCardPromIds 계열은 호출측이 이미 가진 StringBuilder를 그대로 넘겨받아 append만 하므로 반환값에 포함하지 않는다.
	 */
	private CardPromotionTexts buildCardPromotionTexts(JsonNode fareNode, FeeAmountTexts feeAmountTexts, String pasnType, String newSplyCd, Map<String, SchAirFareResultFilterVo> fareFltrMap, StringBuilder sbGnrlCardPromId, StringBuilder sbGnrlCardPromEventCd, StringBuilder sbGnrlCardNm, StringBuilder sbGnrlCardDcInfo, StringBuilder sbGnrlCardDcAplAmt, StringBuilder sbGnrlCardDcTotalAmt, StringBuilder sbDtcmCardPromId, StringBuilder sbDtcmCardPromEventCd, StringBuilder sbDtcmCardNm, StringBuilder sbDtcmCardDcInfo, StringBuilder sbDtcmCardDcAplAmt, StringBuilder sbDtcmCardDcTotalAmt, StringBuilder sbCardPromIds) {
		//######## 카드프로모션정보 #########

		String isExistCardGnrlInfo = "N";
		String isExistCardDtcmInfo = "N";

		StringBuilder sbCardGnrlAdt = new StringBuilder("");
		StringBuilder sbCardGnrlChd = new StringBuilder("");
		StringBuilder sbCardGnrlInf = new StringBuilder("");
		StringBuilder sbCardDtcmAdt = new StringBuilder("");
		StringBuilder sbCardDtcmChd = new StringBuilder("");
		StringBuilder sbCardDtcmInf = new StringBuilder("");

		for(JsonNode cardPromNode : fareNode.path("cardPromLst")){
			//일반카드프로모션정보 setting
			sbGnrlCardPromId.append(     StringUtil.nullConvert(cardPromNode.path("cardPromId").textValue()) + "\n");			//프로모션Id
			sbGnrlCardPromEventCd.append(StringUtil.nullConvert(cardPromNode.path("eventCd"   ).textValue()) + "\n");			//이벤트코드
			sbGnrlCardNm.append(         StringUtil.nullConvert(cardPromNode.path("cardNm"    ).textValue()) + "\n");					//카드명

			String gnrlEventNmForFilter = StringUtil.nullConvert(cardPromNode.path("eventNm"   ).textValue());	// 이벤트명   - 필터용

			String gnrlCardDcRate = cardPromNode.path("totDcRate").asText();
			String gnrlAdtDcAmt = NumberUtil.formatNumber(cardPromNode.path("gnrlAdtDcAmt").asText(), "#,###,###");
			String gnrlChdDcAmt = NumberUtil.formatNumber(cardPromNode.path("gnrlChdDcAmt").asText(), "#,###,###");
			String gnrlInfDcAmt = NumberUtil.formatNumber(cardPromNode.path("gnrlInfDcAmt").asText(), "#,###,###");
			sbGnrlCardDcInfo.append(gnrlCardDcRate + "% / " + gnrlAdtDcAmt + "\n");											//할인정보
			long cardGnrlAdtDcAplSaleAmtL = cardPromNode.path("gnrlAdtDcAplSaleAmt").asLong(0L);							//할인적용금액
			long cardGnrlChdDcAplSaleAmtL = cardPromNode.path("gnrlChdDcAplSaleAmt").asLong(0L);							//할인적용금액
			long cardGnrlInfDcAplSaleAmtL = cardPromNode.path("gnrlInfDcAplSaleAmt").asLong(0L);							//할인적용금액
			sbGnrlCardDcAplAmt.append(pasnType + " " + NumberUtil.formatNumber(cardGnrlAdtDcAplSaleAmtL, "#,###,###"));
			long gnrlAdtCardDcTotalAmtL = cardGnrlAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
			long gnrlChdCardDcTotalAmtL = cardGnrlChdDcAplSaleAmtL + feeAmountTexts.gnrlChdEtcAmtL;												//할인적용 총금액
			long gnrlInfCardDcTotalAmtL = cardGnrlInfDcAplSaleAmtL + feeAmountTexts.gnrlInfEtcAmtL;												//할인적용 총금액
			sbGnrlCardDcTotalAmt.append(" Total " + NumberUtil.formatNumber(gnrlAdtCardDcTotalAmtL, "#,###,###") + "\n");			//할인적용총요금

			String gnrlAdtCardDcTotalAmt = "Total " + NumberUtil.formatNumber(gnrlAdtCardDcTotalAmtL, "#,###,###");			//할인적용총요금
			String gnrlChdCardDcTotalAmt = "Total " + NumberUtil.formatNumber(gnrlChdCardDcTotalAmtL, "#,###,###");			//할인적용총요금
			String gnrlInfCardDcTotalAmt = "Total " + NumberUtil.formatNumber(gnrlInfCardDcTotalAmtL, "#,###,###");			//할인적용총요금

			if(cardGnrlAdtDcAplSaleAmtL != 0){	//더보기 체크용
				isExistCardGnrlInfo = "Y";
			}

			//닷컴카드프로모션정보 setting
			sbDtcmCardPromId.append(     StringUtil.nullConvert(cardPromNode.path("cardPromId").textValue()) + "\n");	//프로모션Id
			sbDtcmCardPromEventCd.append(StringUtil.nullConvert(cardPromNode.path("eventCd"   ).textValue()) + "\n");	//이벤트코드
			sbDtcmCardNm.append(         StringUtil.nullConvert(cardPromNode.path("cardNm"    ).textValue()) + "\n");	//카드명
			String dtcmCardDcRate = cardPromNode.path("totDcRate").asText();
			String dtcmCardDcAmt = NumberUtil.formatNumber(cardPromNode.path("dtcmAdtDcAmt").asText(), "#,###,###");
			sbDtcmCardDcInfo.append(dtcmCardDcRate + "% / " + dtcmCardDcAmt + "\n");											//할인정보
			long cardDtcmAdtDcAplSaleAmtL = cardPromNode.path("dtcmAdtDcAplSaleAmt").asLong(0L);							//할인적용금액
			long cardDtcmChdDcAplSaleAmtL = cardPromNode.path("dtcmChdDcAplSaleAmt").asLong(0L);							//할인적용금액
			long cardDtcmInfDcAplSaleAmtL = cardPromNode.path("dtcmInfDcAplSaleAmt").asLong(0L);							//할인적용금액
			sbDtcmCardDcAplAmt.append(pasnType + " " + NumberUtil.formatNumber(cardDtcmAdtDcAplSaleAmtL, "#,###,###"));
			long dtcmAdtCardDcTotalAmtL = cardDtcmAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
			long dtcmChdCardDcTotalAmtL = cardDtcmChdDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
			long dtcmInfCardDcTotalAmtL = cardDtcmInfDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
			sbDtcmCardDcTotalAmt.append("Total " + NumberUtil.formatNumber(dtcmAdtCardDcTotalAmtL, "#,###,###") + "\n");		//할인적용총요금

			String dtcmAdtCardDcTotalAmt = "Total " + NumberUtil.formatNumber(dtcmAdtCardDcTotalAmtL, "#,###,###");			//할인적용총요금
			String dtcmChdCardDcTotalAmt = "Total " + NumberUtil.formatNumber(dtcmChdCardDcTotalAmtL, "#,###,###");			//할인적용총요금
			String dtcmInfCardDcTotalAmt = "Total " + NumberUtil.formatNumber(dtcmInfCardDcTotalAmtL, "#,###,###");			//할인적용총요금

			if(cardDtcmAdtDcAplSaleAmtL != 0){	//더보기 체크용
				isExistCardDtcmInfo = "Y";
			}

			sbCardPromIds.append(sbGnrlCardPromId.toString() +",");	//필터용값

			String cardPromId = StringUtil.nullConvert(cardPromNode.path("cardPromId").textValue());
			String eventCd    = StringUtil.nullConvert(cardPromNode.path("eventCd"   ).textValue());
			String cardNm     = StringUtil.nullConvert(cardPromNode.path("cardNm"    ).textValue()) + "         ";

			cardNm = cardNm.substring(0, 9);

			String gnrlAdtDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(cardPromNode.path("gnrlAdtDcAplSaleAmt"   ).asText()), "#,###,###");	//
			String gnrlChdDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(cardPromNode.path("gnrlChdDcAplSaleAmt"   ).asText()), "#,###,###");	//
			String gnrlInfDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(cardPromNode.path("gnrlInfDcAplSaleAmt"   ).asText()), "#,###,###");	//
			String dtcmAdtDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(cardPromNode.path("dtcmAdtDcAplSaleAmt"   ).asText()), "#,###,###");	//
			String dtcmChdDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(cardPromNode.path("dtcmChdDcAplSaleAmt"   ).asText()), "#,###,###");	//
			String dtcmInfDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(cardPromNode.path("dtcmInfDcAplSaleAmt"   ).asText()), "#,###,###");	//

			//네이버 스마트
			String smartGnrlAdtAmt = "";
			String smartGnrlChdAmt = "";
			String smartGnrlInfAmt = "";
			String smartDtcmAdtAmt = "";
			String smartDtcmChdAmt = "";
			String smartDtcmInfAmt = "";

			JsonNode smartNode = cardPromNode.path("membershipGrade2");

			if (smartNode.isObject() && !smartNode.isEmpty()) {
				String smartRate = smartNode.path("totDcRate").asText();
				String smartAdtDcAmt = NumberUtil.formatNumber(smartNode.path("gnrlAdtDcAmt").asText(), "#,###,###");
				String smartChdDcAmt = NumberUtil.formatNumber(smartNode.path("gnrlChdDcAmt").asText(), "#,###,###");
				String smartInfDcAmt = NumberUtil.formatNumber(smartNode.path("gnrlInfDcAmt").asText(), "#,###,###");
				String smartDtcmAdtDcAmt = NumberUtil.formatNumber(smartNode.path("dtcmAdtDcAmt").asText(), "#,###,###");
				String smartDtcmChdDcAmt = NumberUtil.formatNumber(smartNode.path("dtcmChdDcAmt").asText(), "#,###,###");
				String smartDtcmInfDcAmt = NumberUtil.formatNumber(smartNode.path("dtcmInfDcAmt").asText(), "#,###,###");
				String smartGnrlAdtDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(smartNode.path("gnrlAdtDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String smartGnrlChdDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(smartNode.path("gnrlChdDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String smartGnrlInfDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(smartNode.path("gnrlInfDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String smartDtcmAdtDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(smartNode.path("dtcmAdtDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String smartDtcmChdDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(smartNode.path("dtcmChdDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String smartDtcmInfDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(smartNode.path("dtcmInfDcAplSaleAmt"   ).asText()), "#,###,###");	//

				long smartCardGnrlAdtDcAplSaleAmtL = smartNode.path("gnrlAdtDcAplSaleAmt").asLong(0L);
				long smartGnrlAdtCardDcTotalAmtL = smartCardGnrlAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
				String smartGnrlAdtCardDcTotalAmt = "Total " + NumberUtil.formatNumber(smartGnrlAdtCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long smartCardGnrlChdDcAplSaleAmtL = smartNode.path("gnrlChdDcAplSaleAmt").asLong(0L);
				long smartGnrlChdCardDcTotalAmtL = smartCardGnrlChdDcAplSaleAmtL + feeAmountTexts.gnrlChdEtcAmtL;												//할인적용 총금액
				String smartGnrlChdCardDcTotalAmt = "Total " + NumberUtil.formatNumber(smartGnrlChdCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long smartCardGnrlInfDcAplSaleAmtL = smartNode.path("gnrlInfDcAplSaleAmt").asLong(0L);
				long smartGnrlInfCardDcTotalAmtL = smartCardGnrlInfDcAplSaleAmtL + feeAmountTexts.gnrlInfEtcAmtL;												//할인적용 총금액
				String smartGnrlInfCardDcTotalAmt = "Total " + NumberUtil.formatNumber(smartGnrlInfCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				smartGnrlAdtAmt = "스마트 "+smartRate + "% / " + smartAdtDcAmt +"\tADT "+ smartGnrlAdtDcAplSaleAmt + "\t"+ smartGnrlAdtCardDcTotalAmt;
				smartGnrlChdAmt = "스마트 "+smartRate + "% / " + smartChdDcAmt +"\tCHD "+ smartGnrlChdDcAplSaleAmt + "\t"+ smartGnrlChdCardDcTotalAmt;
				smartGnrlInfAmt = "스마트 "+smartRate + "% / " + smartInfDcAmt +"\tINF "+ smartGnrlInfDcAplSaleAmt + "\t"+ smartGnrlInfCardDcTotalAmt;

				long smartCardDtcmAdtDcAplSaleAmtL = smartNode.path("dtcmAdtDcAplSaleAmt").asLong(0L);							//할인적용금액
				long smartDtcmAdtCardDcTotalAmtL = smartCardDtcmAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
				String smartDtcmAdtCardDcTotalAmt = "Total " + NumberUtil.formatNumber(smartDtcmAdtCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long smartCardDtcmChdDcAplSaleAmtL = smartNode.path("dtcmChdDcAplSaleAmt").asLong(0L);							//할인적용금액
				long smartDtcmChdCardDcTotalAmtL = smartCardDtcmChdDcAplSaleAmtL + feeAmountTexts.gnrlChdEtcAmtL;												//할인적용 총금액
				String smartDtcmChdCardDcTotalAmt = "Total " + NumberUtil.formatNumber(smartDtcmChdCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long smartCardDtcmInfDcAplSaleAmtL = smartNode.path("dtcmInfDcAplSaleAmt").asLong(0L);							//할인적용금액
				long smartDtcmInfCardDcTotalAmtL = smartCardDtcmInfDcAplSaleAmtL + feeAmountTexts.gnrlInfEtcAmtL;												//할인적용 총금액
				String smartDtcmInfCardDcTotalAmt = "Total " + NumberUtil.formatNumber(smartDtcmInfCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				smartDtcmAdtAmt = "스마트 "+smartRate + "% / " + smartDtcmAdtDcAmt +"\tADT "+ smartDtcmAdtDcAplSaleAmt + "\t"+ smartDtcmAdtCardDcTotalAmt;
				smartDtcmChdAmt = "스마트 "+smartRate + "% / " + smartDtcmChdDcAmt +"\tCHD "+ smartDtcmChdDcAplSaleAmt + "\t"+ smartDtcmChdCardDcTotalAmt;
				smartDtcmInfAmt = "스마트 "+smartRate + "% / " + smartDtcmInfDcAmt +"\tINF "+ smartDtcmInfDcAplSaleAmt + "\t"+ smartDtcmInfCardDcTotalAmt;
			}

			//네이버 골드
			String goldGnrlAdtAmt = "";
			String goldGnrlChdAmt = "";
			String goldGnrlInfAmt = "";
			String goldDtcmAdtAmt = "";
			String goldDtcmChdAmt = "";
			String goldDtcmInfAmt = "";

			JsonNode goldNode = cardPromNode.path("membershipGrade3");

			if (goldNode.isObject() && !goldNode.isEmpty()) {
				String goldRate = goldNode.path("totDcRate").asText();
				String goldAdtDcAmt = NumberUtil.formatNumber(goldNode.path("gnrlAdtDcAmt").asText(), "#,###,###");
				String goldChdDcAmt = NumberUtil.formatNumber(goldNode.path("gnrlChdDcAmt").asText(), "#,###,###");
				String goldInfDcAmt = NumberUtil.formatNumber(goldNode.path("gnrlInfDcAmt").asText(), "#,###,###");
				String goldDtcmAdtDcAmt = NumberUtil.formatNumber(goldNode.path("dtcmAdtDcAmt").asText(), "#,###,###");
				String goldDtcmChdDcAmt = NumberUtil.formatNumber(goldNode.path("dtcmChdDcAmt").asText(), "#,###,###");
				String goldDtcmInfDcAmt = NumberUtil.formatNumber(goldNode.path("dtcmInfDcAmt").asText(), "#,###,###");
				String goldGnrlAdtDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(goldNode.path("gnrlAdtDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String goldGnrlChdDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(goldNode.path("gnrlChdDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String goldGnrlInfDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(goldNode.path("gnrlInfDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String goldDtcmAdtDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(goldNode.path("dtcmAdtDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String goldDtcmChdDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(goldNode.path("dtcmChdDcAplSaleAmt"   ).asText()), "#,###,###");	//
				String goldDtcmInfDcAplSaleAmt = NumberUtil.formatNumber(StringUtil.nullConvert(goldNode.path("dtcmInfDcAplSaleAmt"   ).asText()), "#,###,###");	//

				long goldCardGnrlAdtDcAplSaleAmtL = goldNode.path("gnrlAdtDcAplSaleAmt").asLong(0L);
				long goldGnrlAdtCardDcTotalAmtL = goldCardGnrlAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
				String goldGnrlAdtCardDcTotalAmt = "Total " + NumberUtil.formatNumber(goldGnrlAdtCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long goldCardGnrlChdDcAplSaleAmtL = goldNode.path("gnrlChdDcAplSaleAmt").asLong(0L);
				long goldGnrlChdCardDcTotalAmtL = goldCardGnrlChdDcAplSaleAmtL + feeAmountTexts.gnrlChdEtcAmtL;												//할인적용 총금액
				String goldGnrlChdCardDcTotalAmt = "Total " + NumberUtil.formatNumber(goldGnrlChdCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long goldCardGnrlInfDcAplSaleAmtL = goldNode.path("gnrlInfDcAplSaleAmt").asLong(0L);
				long goldGnrlInfCardDcTotalAmtL = goldCardGnrlInfDcAplSaleAmtL + feeAmountTexts.gnrlInfEtcAmtL;												//할인적용 총금액
				String goldGnrlInfCardDcTotalAmt = "Total " + NumberUtil.formatNumber(goldGnrlInfCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				goldGnrlAdtAmt = "골드 "+goldRate + "% / " + goldAdtDcAmt +"\tADT "+ goldGnrlAdtDcAplSaleAmt + "\t"+ goldGnrlAdtCardDcTotalAmt;
				goldGnrlChdAmt = "골드 "+goldRate + "% / " + goldChdDcAmt +"\tCHD "+ goldGnrlChdDcAplSaleAmt + "\t"+ goldGnrlChdCardDcTotalAmt;
				goldGnrlInfAmt = "골드 "+goldRate + "% / " + goldInfDcAmt +"\tINF "+ goldGnrlInfDcAplSaleAmt + "\t"+ goldGnrlInfCardDcTotalAmt;

				long goldCardDtcmAdtDcAplSaleAmtL = goldNode.path("dtcmAdtDcAplSaleAmt").asLong(0L);							//할인적용금액
				long goldDtcmAdtCardDcTotalAmtL = goldCardDtcmAdtDcAplSaleAmtL + feeAmountTexts.gnrlAdtEtcAmtL;												//할인적용 총금액
				String goldDtcmAdtCardDcTotalAmt = "Total " + NumberUtil.formatNumber(goldDtcmAdtCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long goldCardDtcmChdDcAplSaleAmtL = goldNode.path("dtcmChdDcAplSaleAmt").asLong(0L);							//할인적용금액
				long goldDtcmChdCardDcTotalAmtL = goldCardDtcmChdDcAplSaleAmtL + feeAmountTexts.gnrlChdEtcAmtL;												//할인적용 총금액
				String goldDtcmChdCardDcTotalAmt = "Total " + NumberUtil.formatNumber(goldDtcmChdCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				long goldCardDtcmInfDcAplSaleAmtL = goldNode.path("dtcmInfDcAplSaleAmt").asLong(0L);							//할인적용금액
				long goldDtcmInfCardDcTotalAmtL = goldCardDtcmInfDcAplSaleAmtL + feeAmountTexts.gnrlInfEtcAmtL;												//할인적용 총금액
				String goldDtcmInfCardDcTotalAmt = "Total " + NumberUtil.formatNumber(goldDtcmInfCardDcTotalAmtL, "#,###,###");			//할인적용총요금

				goldDtcmAdtAmt = "골드 "+goldRate + "% / " + goldDtcmAdtDcAmt +"\tADT "+ goldDtcmAdtDcAplSaleAmt + "\t"+ goldDtcmAdtCardDcTotalAmt;
				goldDtcmChdAmt = "골드 "+goldRate + "% / " + goldDtcmChdDcAmt +"\tCHD "+ goldDtcmChdDcAplSaleAmt + "\t"+ goldDtcmChdCardDcTotalAmt;
				goldDtcmInfAmt = "골드 "+goldRate + "% / " + goldDtcmInfDcAmt +"\tINF "+ goldDtcmInfDcAplSaleAmt + "\t"+ goldDtcmInfCardDcTotalAmt;
			}

			sbCardGnrlAdt.append("\n일반 카드 (No." + cardPromId + " - " + eventCd + ") " + cardNm + "\tR " + gnrlCardDcRate + "% / "+ gnrlAdtDcAmt +"\tADT "+ gnrlAdtDcAplSaleAmt + "\t"+ gnrlAdtCardDcTotalAmt+ "	\t"+ smartGnrlAdtAmt + "	\t"+ goldGnrlAdtAmt);
			sbCardGnrlChd.append("\n일반 카드 (No." + cardPromId + " - " + eventCd + ") " + cardNm + "\tR " + gnrlCardDcRate + "% / "+ gnrlChdDcAmt +"\tCHD "+ gnrlChdDcAplSaleAmt + "\t"+ gnrlChdCardDcTotalAmt+ "	\t"+ smartGnrlChdAmt + "	\t"+ goldGnrlChdAmt);
			sbCardGnrlInf.append("\n일반 카드 (No." + cardPromId + " - " + eventCd + ") " + cardNm + "\tR " + gnrlCardDcRate + "% / "+ gnrlInfDcAmt +"\tINF "+ gnrlInfDcAplSaleAmt + "\t"+ gnrlInfCardDcTotalAmt+ "	\t"+ smartGnrlInfAmt + "	\t"+ goldGnrlInfAmt);
			sbCardDtcmAdt.append("\n닷컴 카드 (No." + cardPromId + " - " + eventCd + ") " + cardNm + "\tR " + gnrlCardDcRate + "% / "+ gnrlAdtDcAmt +"\tADT "+ dtcmAdtDcAplSaleAmt + "\t"+ dtcmAdtCardDcTotalAmt+ "	\t"+ smartDtcmAdtAmt + "	\t"+ goldDtcmAdtAmt);
			sbCardDtcmChd.append("\n닷컴 카드 (No." + cardPromId + " - " + eventCd + ") " + cardNm + "\tR " + gnrlCardDcRate + "% / "+ gnrlChdDcAmt +"\tCHD "+ dtcmChdDcAplSaleAmt + "\t"+ dtcmChdCardDcTotalAmt+ "	\t"+ smartDtcmChdAmt + "	\t"+ goldDtcmChdAmt);
			sbCardDtcmInf.append("\n닷컴 카드 (No." + cardPromId + " - " + eventCd + ") " + cardNm + "\tR " + gnrlCardDcRate + "% / "+ gnrlInfDcAmt +"\tINF "+ dtcmInfDcAplSaleAmt + "\t"+ dtcmInfCardDcTotalAmt+ "	\t"+ smartDtcmInfAmt + "	\t"+ goldDtcmInfAmt);

			this.setFilterMap(fareFltrMap, newSplyCd, eventCd, gnrlEventNmForFilter, 0L, FltrType.CARD_PROM_IDS);	//개별탭-필터설정 : 카드프로모션Id
			this.setFilterMap(fareFltrMap, ALL_TAP  , eventCd, gnrlEventNmForFilter, 0L, FltrType.CARD_PROM_IDS);	//통합탭-필터설정 : 카드프로모션Id
		}//카드프로모션정보

		if(StringUtil.isEmpty(sbCardGnrlAdt.toString())) sbCardGnrlAdt.append("\n일반 카드 - \tADT - ");
		if(StringUtil.isEmpty(sbCardGnrlChd.toString())) sbCardGnrlChd.append("\n일반 카드 - \tCHD - ");
		if(StringUtil.isEmpty(sbCardGnrlInf.toString())) sbCardGnrlInf.append("\n일반 카드 - \tINF - ");
		if(StringUtil.isEmpty(sbCardDtcmAdt.toString())) sbCardDtcmAdt.append("\n닷컴 카드 - \tADT - ");
		if(StringUtil.isEmpty(sbCardDtcmChd.toString())) sbCardDtcmChd.append("\n닷컴 카드 - \tCHD - ");
		if(StringUtil.isEmpty(sbCardDtcmInf.toString())) sbCardDtcmInf.append("\n닷컴 카드 - \tINF - ");


		return new CardPromotionTexts(sbCardGnrlAdt, sbCardGnrlChd, sbCardGnrlInf, sbCardDtcmAdt, sbCardDtcmChd, sbCardDtcmInf, isExistCardGnrlInfo, isExistCardDtcmInfo);
	}

	/** buildCardPromotionTexts()의 결과를 담는 불변 보유체. */
	private static final class CardPromotionTexts {
		private final StringBuilder sbCardGnrlAdt;
		private final StringBuilder sbCardGnrlChd;
		private final StringBuilder sbCardGnrlInf;
		private final StringBuilder sbCardDtcmAdt;
		private final StringBuilder sbCardDtcmChd;
		private final StringBuilder sbCardDtcmInf;
		private final String isExistCardGnrlInfo;
		private final String isExistCardDtcmInfo;

		private CardPromotionTexts(StringBuilder sbCardGnrlAdt, StringBuilder sbCardGnrlChd, StringBuilder sbCardGnrlInf, StringBuilder sbCardDtcmAdt, StringBuilder sbCardDtcmChd, StringBuilder sbCardDtcmInf, String isExistCardGnrlInfo, String isExistCardDtcmInfo) {
			this.sbCardGnrlAdt = sbCardGnrlAdt;
			this.sbCardGnrlChd = sbCardGnrlChd;
			this.sbCardGnrlInf = sbCardGnrlInf;
			this.sbCardDtcmAdt = sbCardDtcmAdt;
			this.sbCardDtcmChd = sbCardDtcmChd;
			this.sbCardDtcmInf = sbCardDtcmInf;
			this.isExistCardGnrlInfo = isExistCardGnrlInfo;
			this.isExistCardDtcmInfo = isExistCardDtcmInfo;
		}
	}
	/**
	 * 발권수수료(TASF: 공급/판매/분배/대리점) 텍스트를 조립한다.
	 * farVoLst(편도결합)/farLstNode(비결합)는 필드명이 동일하므로 fareNode 하나로 통일. 원본 로직 무변경.
	 */
	private TasfFeeTexts buildTasfFeeTexts(JsonNode fareNode) {
		//#### 발권수수료정보 #####
		JsonNode feeDtlNode = fareNode.path("feeDtl");

		StringBuilder sbIsueFeeGnrlAdt = new StringBuilder("\n일반 TASF ADT ");
		StringBuilder sbIsueFeeGnrlChd = new StringBuilder("\n일반 TASF CHD ");
		StringBuilder sbIsueFeeGnrlInf = new StringBuilder("\n일반 TASF INF ");
		StringBuilder sbIsueFeeDtcmAdt = new StringBuilder("\n닷컴 TASF ADT ");
		StringBuilder sbIsueFeeDtcmChd = new StringBuilder("\n닷컴 TASF CHD ");
		StringBuilder sbIsueFeeDtcmInf = new StringBuilder("\n닷컴 TASF INF ");

		//--------------------------------------
		// TASF - 공급수수료
		//--------------------------------------
		String splyFeeRuleId = StringUtil.nullConvert(feeDtlNode.path("splyFeeRuleId").textValue());	// 공급수수료룰ID
		if(StringUtil.isEmpty(splyFeeRuleId)) {
			sbIsueFeeGnrlAdt.append("공급 - ");
			sbIsueFeeGnrlChd.append("공급 - ");
			sbIsueFeeGnrlInf.append("공급 - ");
			sbIsueFeeDtcmAdt.append("공급 - ");
			sbIsueFeeDtcmChd.append("공급 - ");
			sbIsueFeeDtcmInf.append("공급 - ");
		}else {
			String splyAdtFeeTrf    = StringUtil.nullConvert(feeDtlNode.path("splyAdtFeeTrf"   ).asText());	// 공급성인수수료요율
			String splyChdFeeTrf    = StringUtil.nullConvert(feeDtlNode.path("splyChdFeeTrf"   ).asText());	// 공급아동수수료요율
			String splyInfFeeTrf    = StringUtil.nullConvert(feeDtlNode.path("splyInfFeeTrf"   ).asText());	// 공급유아수수료요율
			String splyAdtFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("splyAdtFeeKrwAmt").asText());	// 공급성인수수료원화금액
			String splyChdFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("splyChdFeeKrwAmt").asText());	// 공급아동수수료원화금액
			String splyInfFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("splyInfFeeKrwAmt").asText());	// 공급유아수수료원화금액

			// 공급수수료적용방식코드 : R - 정률, A - 정액
			String splyFeeAplMthdCd       = StringUtil.nullConvert(feeDtlNode.path("splyFeeAplMthdCd").textValue());
			if("R".equals(splyFeeAplMthdCd)) {
				//------------------------
				// 정률
				//------------------------
				sbIsueFeeGnrlAdt.append("공급 R " + splyAdtFeeTrf +"% / " + NumberUtil.formatNumber(splyAdtFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeGnrlChd.append("공급 R " + splyChdFeeTrf +"% / " + NumberUtil.formatNumber(splyChdFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeGnrlInf.append("공급 R " + splyInfFeeTrf +"% / " + NumberUtil.formatNumber(splyInfFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeDtcmAdt.append("공급 R " + splyAdtFeeTrf +"% / " + NumberUtil.formatNumber(splyAdtFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeDtcmChd.append("공급 R " + splyChdFeeTrf +"% / " + NumberUtil.formatNumber(splyChdFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeDtcmInf.append("공급 R " + splyInfFeeTrf +"% / " + NumberUtil.formatNumber(splyInfFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
			} else if("A".equals(splyFeeAplMthdCd)) {
				//------------------------
				// 정액
				//------------------------
				sbIsueFeeGnrlAdt.append("공급 A / " + NumberUtil.formatNumber(splyAdtFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeGnrlChd.append("공급 A / " + NumberUtil.formatNumber(splyChdFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeGnrlInf.append("공급 A / " + NumberUtil.formatNumber(splyInfFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeDtcmAdt.append("공급 A / " + NumberUtil.formatNumber(splyAdtFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeDtcmChd.append("공급 A / " + NumberUtil.formatNumber(splyChdFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
				sbIsueFeeDtcmInf.append("공급 A / " + NumberUtil.formatNumber(splyInfFeeKrwAmt, "#,###,###")+" (No."+splyFeeRuleId+") ");
			}
		}
		//--------------------------------------
		// TASF - 판매수수료
		//--------------------------------------
		String saleFeeRuleId = StringUtil.nullConvert(feeDtlNode.path("saleFeeRuleId").textValue());	// 판매수수료룰ID
		if(StringUtil.isEmpty(saleFeeRuleId)) {
			sbIsueFeeGnrlAdt.append(" 판매 - ");
			sbIsueFeeGnrlChd.append(" 판매 - ");
			sbIsueFeeGnrlInf.append(" 판매 - ");
			sbIsueFeeDtcmAdt.append(" 판매 - ");
			sbIsueFeeDtcmChd.append(" 판매 - ");
			sbIsueFeeDtcmInf.append(" 판매 - ");
		}else {
			String saleAdtFeeTrf        = StringUtil.nullConvert(feeDtlNode.path("saleAdtFeeTrf"       ).asText());	// 판매성인수수료요율
			String saleChdFeeTrf        = StringUtil.nullConvert(feeDtlNode.path("saleChdFeeTrf"       ).asText());	// 판매아동수수료요율
			String saleInfFeeTrf        = StringUtil.nullConvert(feeDtlNode.path("saleInfFeeTrf"       ).asText());	// 판매유아수수료요율
			String saleGnrlAdtFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("saleGnrlAdtFeeKrwAmt").asText());	// 판매일반성인수수료원화금액
			String saleGnrlChdFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("saleGnrlChdFeeKrwAmt").asText());	// 판매일반아동수수료원화금액
			String saleGnrlInfFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("saleGnrlInfFeeKrwAmt").asText());	// 판매일반유아수수료원화금액
			String saleDtcmAdtFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("saleDtcmAdtFeeKrwAmt").asText());	// 판매닷컴성인수수료원화금액
			String saleDtcmChdFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("saleDtcmChdFeeKrwAmt").asText());	// 판매닷컴아동수수료원화금액
			String saleDtcmInfFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("saleDtcmInfFeeKrwAmt").asText());	// 판매닷컴유아수수료원화금액

			// 판매수수료적용방식 : R - 정률, A - 정액
			String saleFeeAplMthdCd = StringUtil.nullConvert(feeDtlNode.path("saleFeeAplMthdCd"   ).asText());
			if("R".equals(saleFeeAplMthdCd)) {
				//------------------------
				// 정률
				//------------------------
				sbIsueFeeGnrlAdt.append(" 판매 R " + saleAdtFeeTrf +"% / " + NumberUtil.formatNumber(saleGnrlAdtFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeGnrlChd.append(" 판매 R " + saleChdFeeTrf +"% / " + NumberUtil.formatNumber(saleGnrlChdFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeGnrlInf.append(" 판매 R " + saleInfFeeTrf +"% / " + NumberUtil.formatNumber(saleGnrlInfFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeDtcmAdt.append(" 판매 R " + saleAdtFeeTrf +"% / " + NumberUtil.formatNumber(saleDtcmAdtFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeDtcmChd.append(" 판매 R " + saleChdFeeTrf +"% / " + NumberUtil.formatNumber(saleDtcmChdFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeDtcmInf.append(" 판매 R " + saleInfFeeTrf +"% / " + NumberUtil.formatNumber(saleDtcmInfFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
			} else if("A".equals(saleFeeAplMthdCd)) {
				//------------------------
				// 정액
				//------------------------
				sbIsueFeeGnrlAdt.append(" 판매 A / " + NumberUtil.formatNumber(saleGnrlAdtFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeGnrlChd.append(" 판매 A / " + NumberUtil.formatNumber(saleGnrlChdFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeGnrlInf.append(" 판매 A / " + NumberUtil.formatNumber(saleGnrlInfFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeDtcmAdt.append(" 판매 A / " + NumberUtil.formatNumber(saleDtcmAdtFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeDtcmChd.append(" 판매 A / " + NumberUtil.formatNumber(saleDtcmChdFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
				sbIsueFeeDtcmInf.append(" 판매 A / " + NumberUtil.formatNumber(saleDtcmInfFeeKrwAmt, "#,###,###")+" (No."+saleFeeRuleId+") ");
			}
		}
		//--------------------------------------
		// TASF - 분배
		//--------------------------------------
		String dtrbYn = StringUtil.nullConvert(feeDtlNode.path("dtrbYn"   ).asText());

		if("Y".equals(dtrbYn)) {
			String hanaDtrbRato = StringUtil.nullConvert(feeDtlNode.path("hanaDtrbRato"  ).asText());
			String agtDtrbRato  = StringUtil.nullConvert(feeDtlNode.path("agtDtrbRato"   ).asText());

			String gnrlAdtHanaDtrbKrwAmt = !"".equals(feeDtlNode.path("gnrlAdtHanaDtrbKrwAmt").asText()) ? NumberUtil.formatNumber(feeDtlNode.path("gnrlAdtHanaDtrbKrwAmt").asText(), "#,###,###") : "";	// 일반성인하나투어분배원화금액
			String gnrlChdHanaDtrbKrwAmt = !"".equals(feeDtlNode.path("gnrlChdHanaDtrbKrwAmt").asText()) ? NumberUtil.formatNumber(feeDtlNode.path("gnrlChdHanaDtrbKrwAmt").asText(), "#,###,###") : "";	// 일반아동하나투어분배원화금액
			String gnrlInfHanaDtrbKrwAmt = !"".equals(feeDtlNode.path("gnrlInfHanaDtrbKrwAmt").asText()) ? NumberUtil.formatNumber(feeDtlNode.path("gnrlInfHanaDtrbKrwAmt").asText(), "#,###,###") : "";	// 일반유아하나투어분배원화금액
			String dtcmAdtHanaDtrbKrwAmt = !"".equals(feeDtlNode.path("dtcmAdtHanaDtrbKrwAmt").asText()) ? NumberUtil.formatNumber(feeDtlNode.path("dtcmAdtHanaDtrbKrwAmt").asText(), "#,###,###") : "";	// 닷컴성인하나투어분배원화금액
			String dtcmChdHanaDtrbKrwAmt = !"".equals(feeDtlNode.path("dtcmChdHanaDtrbKrwAmt").asText()) ? NumberUtil.formatNumber(feeDtlNode.path("dtcmChdHanaDtrbKrwAmt").asText(), "#,###,###") : "";	// 닷컴아동하나투어분배원화금액
			String dtcmInfHanaDtrbKrwAmt = !"".equals(feeDtlNode.path("dtcmInfHanaDtrbKrwAmt").asText()) ? NumberUtil.formatNumber(feeDtlNode.path("dtcmInfHanaDtrbKrwAmt").asText(), "#,###,###") : "";	// 닷컴유아하나투어분배원화금액
			String gnrlAdtAgtDtrbKrwAmt  = !"".equals(feeDtlNode.path("gnrlAdtAgtDtrbKrwAmt" ).asText()) ? NumberUtil.formatNumber(feeDtlNode.path("gnrlAdtAgtDtrbKrwAmt" ).asText(), "#,###,###") : "";	// 일반성인대리점분배원화금액
			String gnrlChdAgtDtrbKrwAmt  = !"".equals(feeDtlNode.path("gnrlChdAgtDtrbKrwAmt" ).asText()) ? NumberUtil.formatNumber(feeDtlNode.path("gnrlChdAgtDtrbKrwAmt" ).asText(), "#,###,###") : "";	// 일반아동대리점분배원화금액
			String gnrlInfAgtDtrbKrwAmt  = !"".equals(feeDtlNode.path("gnrlInfAgtDtrbKrwAmt" ).asText()) ? NumberUtil.formatNumber(feeDtlNode.path("gnrlInfAgtDtrbKrwAmt" ).asText(), "#,###,###") : "";	// 일반유아대리점분배원화금액
			String dtcmAdtAgtDtrbKrwAmt  = !"".equals(feeDtlNode.path("dtcmAdtAgtDtrbKrwAmt" ).asText()) ? NumberUtil.formatNumber(feeDtlNode.path("dtcmAdtAgtDtrbKrwAmt" ).asText(), "#,###,###") : "";	// 닷컴성인대리점분배원화금액
			String dtcmChdAgtDtrbKrwAmt  = !"".equals(feeDtlNode.path("dtcmChdAgtDtrbKrwAmt" ).asText()) ? NumberUtil.formatNumber(feeDtlNode.path("dtcmChdAgtDtrbKrwAmt" ).asText(), "#,###,###") : "";	// 닷컴아동대리점분배원화금액
			String dtcmInfAgtDtrbKrwAmt  = !"".equals(feeDtlNode.path("dtcmInfAgtDtrbKrwAmt" ).asText()) ? NumberUtil.formatNumber(feeDtlNode.path("dtcmInfAgtDtrbKrwAmt" ).asText(), "#,###,###") : "";	// 닷컴유아대리점분배원화금액

			sbIsueFeeGnrlAdt.append("(분배" + dtrbYn + " / "+ hanaDtrbRato + " : " + agtDtrbRato +" / "+gnrlAdtHanaDtrbKrwAmt+" : "+gnrlAdtAgtDtrbKrwAmt+")");
			sbIsueFeeGnrlChd.append("(분배" + dtrbYn + " / "+ hanaDtrbRato + " : " + agtDtrbRato +" / "+gnrlChdHanaDtrbKrwAmt+" : "+gnrlChdAgtDtrbKrwAmt+")");
			sbIsueFeeGnrlInf.append("(분배" + dtrbYn + " / "+ hanaDtrbRato + " : " + agtDtrbRato +" / "+gnrlInfHanaDtrbKrwAmt+" : "+gnrlInfAgtDtrbKrwAmt+")");
			sbIsueFeeDtcmAdt.append("(분배" + dtrbYn + " / "+ hanaDtrbRato + " : " + agtDtrbRato +" / "+dtcmAdtHanaDtrbKrwAmt+" : "+dtcmAdtAgtDtrbKrwAmt+")");
			sbIsueFeeDtcmChd.append("(분배" + dtrbYn + " / "+ hanaDtrbRato + " : " + agtDtrbRato +" / "+dtcmChdHanaDtrbKrwAmt+" : "+dtcmChdAgtDtrbKrwAmt+")");
			sbIsueFeeDtcmInf.append("(분배" + dtrbYn + " / "+ hanaDtrbRato + " : " + agtDtrbRato +" / "+dtcmInfHanaDtrbKrwAmt+" : "+dtcmInfAgtDtrbKrwAmt+")");
		} else if("N".equals(dtrbYn)) {
			sbIsueFeeGnrlAdt.append("(분배" + dtrbYn + ")");
			sbIsueFeeGnrlChd.append("(분배" + dtrbYn + ")");
			sbIsueFeeGnrlInf.append("(분배" + dtrbYn + ")");
			sbIsueFeeDtcmAdt.append("(분배" + dtrbYn + ")");
			sbIsueFeeDtcmChd.append("(분배" + dtrbYn + ")");
			sbIsueFeeDtcmInf.append("(분배" + dtrbYn + ")");
		}
		//--------------------------------------
		// TASF - 대리점
		//--------------------------------------
		String agtFeeRuleId = StringUtil.nullConvert(feeDtlNode.path("agtFeeRuleId").textValue());	// 대리점수수료룰ID
		if(StringUtil.isEmpty(agtFeeRuleId)) {
			sbIsueFeeGnrlAdt.append(" 대리점 - ");
			sbIsueFeeGnrlChd.append(" 대리점 - ");
			sbIsueFeeGnrlInf.append(" 대리점 - ");
			sbIsueFeeDtcmAdt.append(" 대리점 - ");
			sbIsueFeeDtcmChd.append(" 대리점 - ");
			sbIsueFeeDtcmInf.append(" 대리점 - ");
		}else {
			String agtAdtFeeTrf        = StringUtil.nullConvert(feeDtlNode.path("agtAdtFeeTrf"       ).asText());	// 대리점성인수수료요율
			String agtChdFeeTrf        = StringUtil.nullConvert(feeDtlNode.path("agtChdFeeTrf"       ).asText());	// 대리점아동수수료요율
			String agtInfFeeTrf        = StringUtil.nullConvert(feeDtlNode.path("agtInfFeeTrf"       ).asText());	// 대리점유아수수료요율
			String agtGnrlAdtFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("agtGnrlAdtFeeKrwAmt").asText());	// 대리점일반성인수수료원화금액
			String agtGnrlChdFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("agtGnrlChdFeeKrwAmt").asText());	// 대리점일반아동수수료원화금액
			String agtGnrlInfFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("agtGnrlInfFeeKrwAmt").asText());	// 대리점일반유아수수료원화금액
			String agtDtcmAdtFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("agtDtcmAdtFeeKrwAmt").asText());	// 대리점닷컴성인수수료원화금액
			String agtDtcmChdFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("agtDtcmChdFeeKrwAmt").asText());	// 대리점닷컴아동수수료원화금액
			String agtDtcmInfFeeKrwAmt = StringUtil.nullConvert(feeDtlNode.path("agtDtcmInfFeeKrwAmt").asText());	// 대리점닷컴유아수수료원화금액

			// 대리점수수료적용방식 : R - 정률, A - 정액
			String agtFeeAplMthdCd = StringUtil.nullConvert(feeDtlNode.path("agtFeeAplMthdCd"   ).asText());
			if("R".equals(agtFeeAplMthdCd)) {
				//------------------------
				// 정률
				//------------------------
				sbIsueFeeGnrlAdt.append(" 대리점 R" + agtAdtFeeTrf +"% / " + NumberUtil.formatNumber(agtGnrlAdtFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeGnrlChd.append(" 대리점 R" + agtChdFeeTrf +"% / " + NumberUtil.formatNumber(agtGnrlChdFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeGnrlInf.append(" 대리점 R" + agtInfFeeTrf +"% / " + NumberUtil.formatNumber(agtGnrlInfFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeDtcmAdt.append(" 대리점 R" + agtAdtFeeTrf +"% / " + NumberUtil.formatNumber(agtDtcmAdtFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeDtcmChd.append(" 대리점 R" + agtChdFeeTrf +"% / " + NumberUtil.formatNumber(agtDtcmChdFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeDtcmInf.append(" 대리점 R" + agtInfFeeTrf +"% / " + NumberUtil.formatNumber(agtDtcmInfFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
			} else if("A".equals(agtFeeAplMthdCd)) {
				//------------------------
				// 정액
				//------------------------
				sbIsueFeeGnrlAdt.append(" 대리점 A / " + NumberUtil.formatNumber(agtGnrlAdtFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeGnrlChd.append(" 대리점 A / " + NumberUtil.formatNumber(agtGnrlChdFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeGnrlInf.append(" 대리점 A / " + NumberUtil.formatNumber(agtGnrlInfFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeDtcmAdt.append(" 대리점 A / " + NumberUtil.formatNumber(agtDtcmAdtFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeDtcmChd.append(" 대리점 A / " + NumberUtil.formatNumber(agtDtcmChdFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
				sbIsueFeeDtcmInf.append(" 대리점 A / " + NumberUtil.formatNumber(agtDtcmInfFeeKrwAmt, "#,###,###")+" (No."+agtFeeRuleId+") ");
			}
		}


		return new TasfFeeTexts(sbIsueFeeGnrlAdt, sbIsueFeeGnrlChd, sbIsueFeeGnrlInf, sbIsueFeeDtcmAdt, sbIsueFeeDtcmChd, sbIsueFeeDtcmInf);
	}

	/** buildTasfFeeTexts()의 결과를 담는 불변 보유체. */
	private static final class TasfFeeTexts {
		private final StringBuilder sbIsueFeeGnrlAdt;
		private final StringBuilder sbIsueFeeGnrlChd;
		private final StringBuilder sbIsueFeeGnrlInf;
		private final StringBuilder sbIsueFeeDtcmAdt;
		private final StringBuilder sbIsueFeeDtcmChd;
		private final StringBuilder sbIsueFeeDtcmInf;

		private TasfFeeTexts(StringBuilder sbIsueFeeGnrlAdt, StringBuilder sbIsueFeeGnrlChd, StringBuilder sbIsueFeeGnrlInf, StringBuilder sbIsueFeeDtcmAdt, StringBuilder sbIsueFeeDtcmChd, StringBuilder sbIsueFeeDtcmInf) {
			this.sbIsueFeeGnrlAdt = sbIsueFeeGnrlAdt;
			this.sbIsueFeeGnrlChd = sbIsueFeeGnrlChd;
			this.sbIsueFeeGnrlInf = sbIsueFeeGnrlInf;
			this.sbIsueFeeDtcmAdt = sbIsueFeeDtcmAdt;
			this.sbIsueFeeDtcmChd = sbIsueFeeDtcmChd;
			this.sbIsueFeeDtcmInf = sbIsueFeeDtcmInf;
		}
	}
	/**
	 * 특별적립마일리지(일반/닷컴, 성인/아동) 텍스트를 조립한다.
	 * farVoLst(편도결합)/farLstNode(비결합)는 필드명이 동일하므로 fareNode 하나로 통일. 원본 로직 무변경.
	 */
	private SpclColtMlgTexts buildSpclColtMlgTexts(JsonNode fareNode) {
		//--------------------------------------
		// 특별적립마일리지금액
		//--------------------------------------
		long gnrlAdtSpclColtMlgTrfL = fareNode.path("gnrlAdtSpclColtMlgTrf").asLong(0);	//일반성인특별적립마일리지율
		long gnrlChdSpclColtMlgTrfL = fareNode.path("gnrlChdSpclColtMlgTrf").asLong(0);	//일반아동특별적립마일리지율
		long dtcmAdtSpclColtMlgTrfL = fareNode.path("dtcmAdtSpclColtMlgTrf").asLong(0);	//닷컴성인특별적립마일리지율
		long dtcmChdSpclColtMlgTrfL = fareNode.path("dtcmChdSpclColtMlgTrf").asLong(0);	//닷컴아동특별적립마일리지율

		long gnrlAdtSpclColtMlgAmtL = fareNode.path("gnrlAdtSpclColtMlgAmt").asLong(0);	//일반성인특별적립마일리지금액
		long gnrlChdSpclColtMlgAmtL = fareNode.path("gnrlChdSpclColtMlgAmt").asLong(0);	//일반아동특별적립마일리지금액
		long dtcmAdtSpclColtMlgAmtL = fareNode.path("dtcmAdtSpclColtMlgAmt").asLong(0);	//닷컴성인특별적립마일리지금액
		long dtcmChdSpclColtMlgAmtL = fareNode.path("dtcmChdSpclColtMlgAmt").asLong(0);	//닷컴아동특별적립마일리지금액

		StringBuilder sbGnrlAdtSpclColtMlgAmt = new StringBuilder("");
		StringBuilder sbGnrlChdSpclColtMlgAmt = new StringBuilder("");
		StringBuilder sbDtcmAdtSpclColtMlgAmt = new StringBuilder("");
		StringBuilder sbDtcmChdSpclColtMlgAmt = new StringBuilder("");

		if(gnrlAdtSpclColtMlgAmtL != 0) {
			sbGnrlAdtSpclColtMlgAmt.append("\n일반 특별적립마일리지 ADT ");
			sbGnrlAdtSpclColtMlgAmt.append(NumberUtil.formatNumber(gnrlAdtSpclColtMlgTrfL, "#,###,###"));
			if(gnrlAdtSpclColtMlgTrfL < 100) sbGnrlAdtSpclColtMlgAmt.append("% / ");		// 율과 금액 동일필드로 사용함으로 100 미만일 경우 '%' 붙임
			sbGnrlAdtSpclColtMlgAmt.append(NumberUtil.formatNumber(gnrlAdtSpclColtMlgAmtL, "#,###,###"));
		}
		if(gnrlChdSpclColtMlgAmtL != 0) {
			sbGnrlChdSpclColtMlgAmt.append("\n일반 특별적립마일리지 CHD ");
			sbGnrlChdSpclColtMlgAmt.append(NumberUtil.formatNumber(gnrlChdSpclColtMlgTrfL, "#,###,###"));
			if(gnrlChdSpclColtMlgTrfL < 100) sbGnrlChdSpclColtMlgAmt.append("% / ");		// 율과 금액 동일필드로 사용함으로 100 미만일 경우 '%' 붙임
			sbGnrlChdSpclColtMlgAmt.append(NumberUtil.formatNumber(gnrlChdSpclColtMlgAmtL, "#,###,###"));
		}
		if(dtcmAdtSpclColtMlgAmtL != 0) {
			sbDtcmAdtSpclColtMlgAmt.append("\n닷컴 특별적립마일리지 ADT ");
			sbDtcmAdtSpclColtMlgAmt.append(NumberUtil.formatNumber(dtcmAdtSpclColtMlgTrfL, "#,###,###"));
			if(dtcmAdtSpclColtMlgTrfL < 100) sbDtcmAdtSpclColtMlgAmt.append("% / ");		// 율과 금액 동일필드로 사용함으로 100 미만일 경우 '%' 붙임
			sbDtcmAdtSpclColtMlgAmt.append(NumberUtil.formatNumber(dtcmAdtSpclColtMlgAmtL, "#,###,###"));
		}
		if(dtcmChdSpclColtMlgAmtL != 0) {
			sbDtcmChdSpclColtMlgAmt.append("\n닷컴 특별적립마일리지 CHD ");
			sbDtcmChdSpclColtMlgAmt.append(NumberUtil.formatNumber(dtcmChdSpclColtMlgTrfL, "#,###,###"));
			if(dtcmChdSpclColtMlgTrfL < 100) sbDtcmChdSpclColtMlgAmt.append("% / ");		// 율과 금액 동일필드로 사용함으로 100 미만일 경우 '%' 붙임
			sbDtcmChdSpclColtMlgAmt.append(NumberUtil.formatNumber(dtcmChdSpclColtMlgAmtL, "#,###,###"));
		}
		//--------------------------------------


		return new SpclColtMlgTexts(sbGnrlAdtSpclColtMlgAmt, sbGnrlChdSpclColtMlgAmt, sbDtcmAdtSpclColtMlgAmt, sbDtcmChdSpclColtMlgAmt);
	}

	/** buildSpclColtMlgTexts()의 결과를 담는 불변 보유체. */
	private static final class SpclColtMlgTexts {
		private final StringBuilder sbGnrlAdtSpclColtMlgAmt;
		private final StringBuilder sbGnrlChdSpclColtMlgAmt;
		private final StringBuilder sbDtcmAdtSpclColtMlgAmt;
		private final StringBuilder sbDtcmChdSpclColtMlgAmt;

		private SpclColtMlgTexts(StringBuilder sbGnrlAdtSpclColtMlgAmt, StringBuilder sbGnrlChdSpclColtMlgAmt, StringBuilder sbDtcmAdtSpclColtMlgAmt, StringBuilder sbDtcmChdSpclColtMlgAmt) {
			this.sbGnrlAdtSpclColtMlgAmt = sbGnrlAdtSpclColtMlgAmt;
			this.sbGnrlChdSpclColtMlgAmt = sbGnrlChdSpclColtMlgAmt;
			this.sbDtcmAdtSpclColtMlgAmt = sbDtcmAdtSpclColtMlgAmt;
			this.sbDtcmChdSpclColtMlgAmt = sbDtcmChdSpclColtMlgAmt;
		}
	}












	
	/** 
	 * 필터맵 값 설정
	 */
	private void setFilterMap(Map<String, SchAirFareResultFilterVo> fltrMap, String splyCd, String aItmCd, String aItmNm, Long adtTamt, FltrType fltrType){	
		if(StringUtil.isEmpty(aItmCd)){
			return;
		}
		
		String keyStr = splyCd + "_" + fltrType.getFltrFld() + "_" + aItmCd;

		switch(fltrType){
		case CARD_PROM_IDS: 
		case GNRL_EVENT_CODE: 
		case DC_EVENT_CODE: 
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setAitmCd(aItmCd);
				filterVo.setAitmNm(aItmNm);
				filterVo.setAitmCnt(1);
				
				switch(fltrType){
				case CARD_PROM_IDS: 
				case GNRL_EVENT_CODE: 
					filterVo.setFltrTxt(fltrType.getFltrFld()+".indexOf('"+aItmCd+"')!=-1");
					break;
				default:
					filterVo.setFltrTxt(fltrType.getFltrFld()+"=='"+aItmCd+"'");
				}
				
				fltrMap.put(keyStr, filterVo);
			}else {
				Integer fltri = fltrMap.get(keyStr).getAitmCnt();
				fltrMap.get(keyStr).setAitmCnt(++fltri);
			}
			break;
		case VIA_CNT: 
		case TKT_AL_CODE:
		case MKT_AL_CODES:
		case OPR_AL_CODES: 		
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setAitmCd(aItmCd);
				filterVo.setAitmNm(aItmNm);
				filterVo.setLowestFare(adtTamt);
				filterVo.setLowestFareStr(NumberUtil.formatNumber(String.valueOf(adtTamt), "#,###,###")+"원~");
				filterVo.setAitmCnt(1);
				
				if(FltrType.MKT_AL_CODES.equals(fltrType) ||  FltrType.OPR_AL_CODES.equals(fltrType)){
					filterVo.setFltrTxt(fltrType.getFltrFld()+".indexOf('"+aItmCd+"')!=-1");
				}else {
					filterVo.setFltrTxt(fltrType.getFltrFld()+"=='"+aItmCd+"'");
				}
				
				fltrMap.put(keyStr, filterVo);
			}else {
				Long tmpLowFare = fltrMap.get(keyStr).getLowestFare();
				if(Long.compare(adtTamt, tmpLowFare) < 0) {
					fltrMap.get(keyStr).setLowestFare(adtTamt);
					fltrMap.get(keyStr).setLowestFareStr(NumberUtil.formatNumber(String.valueOf(adtTamt), "#,###,###")+"원~");
				}

				Integer fltri = fltrMap.get(keyStr).getAitmCnt();
				fltrMap.get(keyStr).setAitmCnt(++fltri);
			}
			break;
		case SPLY_CD:
		case PASN_TYPES: 
		case CABIN_TYPES: 
		case FARE_TYPES:
		case ACCT_CODES:
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setAitmCd(aItmCd);
				filterVo.setAitmNm(aItmNm);
				filterVo.setAitmCnt(1);
				
				if(FltrType.SPLY_CD.equals(fltrType)){
					filterVo.setFltrTxt(fltrType.getFltrFld()+"=='"+aItmCd+"'");
				}else {
					filterVo.setFltrTxt(fltrType.getFltrFld()+".indexOf('"+aItmCd+"')!=-1");
				}

				fltrMap.put(keyStr, filterVo);
			}else {
				Integer fltri = fltrMap.get(keyStr).getAitmCnt();
				fltrMap.get(keyStr).setAitmCnt(++fltri);
			}
			break;
		case CABIN_COMPLEX: 
		case AL_COMPLEX: 
		case IMDT_PAY_PSBL_YN: 
		case FIX_FAR_YN:
		case ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN:
		case MCT_RULE_FAR_EXLS_TRGT_YN:
			if("Y".equals(aItmCd)){
				switch(fltrType){
				case CABIN_COMPLEX: 
				case AL_COMPLEX: 
					aItmNm = "결합";
					break;
				case IMDT_PAY_PSBL_YN: 
					aItmNm = "즉시결제";
					break;
				case FIX_FAR_YN: 
					aItmNm = "운임확정";
					break;
				case ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN: 
					aItmNm = "노출제어 대상";
					break;
				case MCT_RULE_FAR_EXLS_TRGT_YN: 
					aItmNm = "노출제어 대상";
					break;
				default:
				}
			}else {
				switch(fltrType){
				case CABIN_COMPLEX: 
				case AL_COMPLEX: 
					aItmNm = "미결합";
					break;
				case IMDT_PAY_PSBL_YN: 
					aItmNm = "추후결제";
					break;
				case FIX_FAR_YN: 
					aItmNm = "운임미확정";
					break;
				case ISUE_AIRL_RULE_FAR_EXLS_TRGT_YN: 
					aItmNm = "노출제어 비대상";
					break;
				case MCT_RULE_FAR_EXLS_TRGT_YN: 
					aItmNm = "노출제어 비대상";
					break;
				default:
				}
			}
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setAitmCd(aItmCd);
				filterVo.setAitmNm(aItmNm);
				filterVo.setAitmCnt(1);
				filterVo.setFltrTxt(fltrType.getFltrFld() +"=='"+aItmCd+"'");

				fltrMap.put(keyStr, filterVo);
			}else {
				Integer fltri = fltrMap.get(keyStr).getAitmCnt();
				fltrMap.get(keyStr).setAitmCnt(++fltri);
			}
			break;
		case ADT_T_AMT:
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setAitmCd(aItmCd);
				filterVo.setAitmNm(aItmNm);
				filterVo.setAmtFltrMinVal(adtTamt);
				filterVo.setAmtFltrMaxVal(adtTamt);

				fltrMap.put(keyStr, filterVo);
			}else {
				long amtMin = fltrMap.get(keyStr).getAmtFltrMinVal();
				long amtMan= fltrMap.get(keyStr).getAmtFltrMaxVal();
				
				if(Long.compare(adtTamt, amtMin) < 0 ){
					fltrMap.get(keyStr).setAmtFltrMinVal(adtTamt);
				}
				
				if(Long.compare(adtTamt, amtMan) > 0 ){
					fltrMap.get(keyStr).setAmtFltrMaxVal(adtTamt);
				}
			}
			break;

		default:
			break;
		}
	}
	
	/**
	 * 타임필터 설정
	 */
	private void setTimeFilterMap(Map<String, SchAirFareResultFilterVo> fltrMap, String splyCd, int timeVal, FltrType fltrType){	

		String keyStr = splyCd + "_" + fltrType.getFltrFld();
		
		switch(fltrType){
		case TOT_TIME:
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setTotTimeFltrMinVal(timeVal);
				filterVo.setTotTimeFltrMaxVal(timeVal);
				
				fltrMap.put(keyStr, filterVo);
			}else {
				int totTimeMin = fltrMap.get(keyStr).getTotTimeFltrMinVal();
				int totTimeMax = fltrMap.get(keyStr).getTotTimeFltrMaxVal();
							
				if(timeVal < totTimeMin){
					fltrMap.get(keyStr).setTotTimeFltrMinVal(timeVal);
				}
				
				if(timeVal > totTimeMax){
					fltrMap.get(keyStr).setTotTimeFltrMaxVal(timeVal);
				}
			}
			break;
		case STOP_TIME:
			if(!fltrMap.containsKey(keyStr)) {
				SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
				filterVo.setSplyCd(splyCd);
				filterVo.setFilterDv(fltrType.getFltrFld());
				filterVo.setStopTimeFltrMinVal(0);
				filterVo.setStopTimeFltrMaxVal(timeVal);
				
				fltrMap.put(keyStr, filterVo);
			}else {
				int stopTimeMax = fltrMap.get(keyStr).getStopTimeFltrMaxVal();
				
				if(timeVal > stopTimeMax){
					fltrMap.get(keyStr).setStopTimeFltrMaxVal(timeVal);
				}
			}
			break;
		default:
			break;
		
		}
	}

	private void setCombFilterMap(Map<String, SchAirFareResultFilterVo> fltrMap, String splyCd, String aItmCd, String aItmNm, Long adtTamt, FltrType fltrType){
		if(StringUtil.isEmpty(aItmCd)){
			return;
		}

		String keyStr = splyCd + "_" + fltrType.getFltrFld() + "_" + aItmCd;

		switch(fltrType){

			case AIR_FAR_COMB_YN:
				if(!fltrMap.containsKey(keyStr)) {
					SchAirFareResultFilterVo filterVo = new SchAirFareResultFilterVo();
					filterVo.setSplyCd(splyCd);
					filterVo.setFilterDv(fltrType.getFltrFld());
					filterVo.setAitmCd(aItmCd);
					filterVo.setAitmNm(aItmNm);
					filterVo.setAitmCnt(1);

					filterVo.setFltrTxt(fltrType.getFltrFld() +"=='"+aItmCd+"'");

					fltrMap.put(keyStr, filterVo);
				}else {
					Integer fltri = fltrMap.get(keyStr).getAitmCnt();
					fltrMap.get(keyStr).setAitmCnt(++fltri);
				}
			break;


			default:
				break;
		}
	}

	/**
	 * 수치범위 필터용 Map setting
	 */
	private void setRangeFilter(List<SchAirFareResultFilterVo> fltrList, SchAirFareResultFilterVo fVo, FltrType fltrType){	

		switch(fltrType){
		case ADT_T_AMT:
			Long gapNum = (fVo.getAmtFltrMaxVal() - fVo.getAmtFltrMinVal()) / 5;

			Long stVal = fVo.getAmtFltrMinVal();
			Long endVal = 0L;
			
			if(gapNum == 0){
				String fltrTxt = fltrType.getFltrFld() + " >= " + stVal.toString();
				
				SchAirFareResultFilterVo nfVo = new SchAirFareResultFilterVo();
				nfVo.setSplyCd(fVo.getSplyCd());
				nfVo.setFilterDv(fVo.getFilterDv());
				nfVo.setAitmCd("1");
				nfVo.setAitmNm(NumberUtil.formatNumber(String.valueOf(stVal), "#,###,###") + "원");
				nfVo.setFltrTxt(fltrTxt);
				
				fltrList.add(nfVo);
			}else {

				for(int i=1; i <=5; i++){
					endVal = Long.sum(stVal, gapNum);
					String fltrTxt = fltrType.getFltrFld() + " >= " + stVal.toString() + " && " + fltrType.getFltrFld() + " <= " + endVal.toString();
					
					SchAirFareResultFilterVo nfVo = new SchAirFareResultFilterVo();
					nfVo.setSplyCd(fVo.getSplyCd());
					nfVo.setFilterDv(fVo.getFilterDv());
					nfVo.setAitmCd(String.valueOf(i));
					nfVo.setAitmNm(NumberUtil.formatNumber(String.valueOf(stVal), "#,###,###") + "원 ~ "+ NumberUtil.formatNumber(String.valueOf(endVal), "#,###,###") + "원");
					nfVo.setFltrTxt(fltrTxt);
					
					stVal = endVal;
	
					fltrList.add(nfVo);
				}
			}
			break;
			
		case TOT_TIME: 
			int totMinVal = fVo.getTotTimeFltrMinVal();
			int totMaxVal = fVo.getTotTimeFltrMaxVal();
			
			Integer totGapNum = (totMaxVal - totMinVal) / 5;

			Integer totStVal = totMinVal;
			Integer totEndVal = 0;		

			if(totGapNum == 0){
				String stStr = this.getTimeStr(totStVal);
				String fltrTxt = fltrType.getFltrFld() + " >= " + totStVal.toString();
				
				SchAirFareResultFilterVo nfVo = new SchAirFareResultFilterVo();
				nfVo.setSplyCd(fVo.getSplyCd());
				nfVo.setFilterDv(fVo.getFilterDv());
				nfVo.setAitmCd("1");
				nfVo.setAitmNm(stStr);
				nfVo.setFltrTxt(fltrTxt);
				
				fltrList.add(nfVo);
			}else {
			
				for(int i=1; i <=5; i++){
	
					if(i == 5){
						totEndVal = totMaxVal;
					}else {
						totEndVal = totStVal + totGapNum;
					}
	
					String stStr = this.getTimeStr(totStVal);
					String endStr = this.getTimeStr(totEndVal);
					String fltrTxt = fltrType.getFltrFld() + " >= " + totStVal.toString() + " && " + fltrType.getFltrFld() + " <= " + totEndVal.toString();
					
					SchAirFareResultFilterVo nfVo = new SchAirFareResultFilterVo();
					nfVo.setSplyCd(fVo.getSplyCd());
					nfVo.setFilterDv(fVo.getFilterDv());
					nfVo.setAitmCd(String.valueOf(i));
					nfVo.setAitmNm(stStr + " ~ " + endStr);
					nfVo.setFltrTxt(fltrTxt);
					
					totStVal = totEndVal;
	
					fltrList.add(nfVo);
				}
			}
			break;

		case STOP_TIME:
			int stopMinVal = fVo.getStopTimeFltrMinVal();
			int stopMaxVal = fVo.getStopTimeFltrMaxVal();
			
			if(stopMaxVal == 0){
				return;
			}

			Integer stopGapNum = (stopMaxVal - stopMinVal) / 5;

			Integer stopStVal = stopMinVal;
			Integer stopEndVal = 0;
			
			if(stopGapNum == 0){
				String stStr = this.getTimeStr(stopStVal);
				String fltrTxt = fltrType.getFltrFld() + " >= " + stopStVal.toString();
				
				SchAirFareResultFilterVo nfVo = new SchAirFareResultFilterVo();
				nfVo.setSplyCd(fVo.getSplyCd());
				nfVo.setFilterDv(fVo.getFilterDv());
				nfVo.setAitmCd("1");
				nfVo.setAitmNm(stStr);
				nfVo.setFltrTxt(fltrTxt);
				
				fltrList.add(nfVo);
			}else {

				for(int i=1; i <=5; i++){
	
					if(i == 5){
						stopEndVal = stopMaxVal;
					}else {
						stopEndVal = stopStVal + stopGapNum;
					}
	
					String stStr = this.getTimeStr(stopStVal);
					String endStr = this.getTimeStr(stopEndVal);
					String fltrTxt = fltrType.getFltrFld() + " >= " + stopStVal.toString() + " && " + fltrType.getFltrFld() + " <= " + stopEndVal.toString();
					
					SchAirFareResultFilterVo nfVo = new SchAirFareResultFilterVo();
					nfVo.setSplyCd(fVo.getSplyCd());
					nfVo.setFilterDv(fVo.getFilterDv());
					nfVo.setAitmCd(String.valueOf(i));
					nfVo.setAitmNm(stStr + " ~ " + endStr);
					nfVo.setFltrTxt(fltrTxt);
					
					stopStVal = stopEndVal;
	
					fltrList.add(nfVo);
				}
			}
			break;

		default:
			break;
		}
	}


	/**
	 * 시간계산용 시,분 -> 분으로 치환
	 */
	private int getMinVal(String timeStr){
		int rMin = 0;
		if(StringUtil.isEmpty(timeStr) || "0".equals(timeStr)){
			return rMin;
		}
		String fTimeStr = StringUtils.leftPad(timeStr, 4, "0");
		rMin = (Integer.valueOf(StringUtils.left(fTimeStr, 2)) * 60) + Integer.valueOf(StringUtils.right(fTimeStr, 2));
		return rMin;
	}

	/**
	 * 시간계산용 분 -> 시,분으로 치환
	 */
	private String getTimeStr(Integer minVal){
		String timeStr = "";

		Integer hourStr = minVal / 60;
		Integer minStr = minVal % 60;
		timeStr = hourStr + "시간" + minStr + "분";
		return timeStr;
	}

	private String createReqJsonObj(SchAirFareSearchVo schAirFareSearchVo) {
		JSONObject reqObj = new JSONObject();
		JSONObject bizComObj = new JSONObject();
		JSONObject itnrDepObj = new JSONObject();
		JSONObject itnrArrObj = new JSONObject();
		JSONArray itnrList = new JSONArray();
		JSONObject psngrObj = new JSONObject();
		JSONArray psngrList = new JSONArray();

		reqObj.put("searchType", "A");
		reqObj.put("cacheSearchYn", "Y");
		reqObj.put("searchCurrCnt", 1);
		reqObj.put("rirtDvCd", "RI");
		reqObj.put("itnrTypeCd", schAirFareSearchVo.getTripType());
		reqObj.put("seatGradCd", schAirFareSearchVo.getSeatGradCd());
		reqObj.put("isueScheDt", "");
		reqObj.put("nonStopOnly", "N");
		reqObj.put("ruleExprCntlDebugFg", schAirFareSearchVo.getRuleExprCntlDebugFg());

		//bizCom Setting
		bizComObj.put("memcSiteCd", "C87090S001"); // 회원사사이트코드 : 일단 단하나여행(A9KR6)의 회원사사이트코드(C87090S001)로 테스트 셋팅
		bizComObj.put("saleChnlCd", schAirFareSearchVo.getSaleChnlCd());
		bizComObj.put("resPathCd" , schAirFareSearchVo.getResPathCd());
		bizComObj.put("airSiteCd" , schAirFareSearchVo.getSaleSiteCd());
		bizComObj.put("ptnCd"     , schAirFareSearchVo.getPtnCd());
		bizComObj.put("unfyMemNum", null);
		bizComObj.put("staffUsrId", null);
		bizComObj.put("saleCurrency", "KRW");
		bizComObj.put("saleCountry", "KR");
		bizComObj.put("langCode", "KOKR");
		bizComObj.put("maxLimitRows", -1);

		//itnrList Setting
		itnrDepObj.put("depPlcCd",schAirFareSearchVo.getDepPlcCd());
		itnrDepObj.put("depPlcDvCd",schAirFareSearchVo.getDepPlcDvCd());
		itnrDepObj.put("arrPlcCd",schAirFareSearchVo.getArrPlcCd());
		itnrDepObj.put("arrPlcDvCd",schAirFareSearchVo.getArrPlcDvCd());
		itnrDepObj.put("depDt",schAirFareSearchVo.getTrvlDepDt());

		itnrArrObj.put("depPlcCd", schAirFareSearchVo.getArrPlcCd());
		itnrArrObj.put("depPlcDvCd", schAirFareSearchVo.getArrPlcDvCd());
		itnrArrObj.put("arrPlcCd", schAirFareSearchVo.getDepPlcCd());
		itnrArrObj.put("arrPlcDvCd", schAirFareSearchVo.getDepPlcDvCd());
		itnrArrObj.put("depDt", schAirFareSearchVo.getTrvlArrDt());

		itnrList.add(itnrDepObj);
		itnrList.add(itnrArrObj);

		//psngrCntLst Setting
		psngrObj.put("ageDvCd", schAirFareSearchVo.getAgeDvCd());
		psngrObj.put("psngrCnt", schAirFareSearchVo.getPsngrCnt());		

		psngrList.add(psngrObj);

		//API Request Json setting
		reqObj.put("bizCom", bizComObj);
		reqObj.put("itnrLst", itnrList);
		reqObj.put("psngrCntLst", psngrList);
		String jsonString = CoreUtil.getJsonUtil().toJson(reqObj);

		return jsonString;
	}
	
	@SuppressWarnings({"unchecked", "unused"})
	private String createReqJsonObj2(SchAirFareChangeSearchVo schAirFareChangeSearchVo) {
		JSONObject reqObj      = new JSONObject();
		JSONObject headerObj   = new JSONObject();
		JSONObject bizComObj   = new JSONObject();
		JSONArray itnrList     = new JSONArray();
		JSONObject itnrDepObj1 = new JSONObject(); 
		JSONObject itnrDepObj2 = new JSONObject(); 
		JSONObject itnrDepObj3 = new JSONObject(); 
		JSONObject itnrDepObj4 = new JSONObject(); 
		JSONObject itnrDepObj5 = new JSONObject(); 
		JSONObject itnrDepObj6 = new JSONObject(); 
		
		JSONArray psngrList  = new JSONArray();
		JSONObject psngrObj1 = new JSONObject();
		JSONObject psngrObj2 = new JSONObject();
		JSONObject psngrObj3 = new JSONObject();

		//basicInfo Setting
		String cacheSearchYn = schAirFareChangeSearchVo.getCacheSearchYn(); //캐시사용여부
		reqObj.put("searchType"   , "A");
		reqObj.put("cacheSearchYn", cacheSearchYn);
		reqObj.put("searchCurrCnt", 1);
		reqObj.put("rirtDvCd"     , "N".equals(cacheSearchYn)?"RIRT":"RI");
		reqObj.put("itnrTypeCd"   , schAirFareChangeSearchVo.getTripType());
		reqObj.put("seatGradCd"   , schAirFareChangeSearchVo.getSeatGradCd());
		reqObj.put("isueScheDt"   , "");
		reqObj.put("nonStopOnly"  ,         schAirFareChangeSearchVo.getNonStopOnly());
//		reqObj.put("ruleExprCntlDebugFg", schAirFareChangeSearchVo.getRuleExprCntlDebugFg());

		//bizCom Setting
		bizComObj.put("memcSiteCd"  , schAirFareChangeSearchVo.getMemcSiteCd());
		bizComObj.put("resPathCd"   , schAirFareChangeSearchVo.getResPathCd());
		bizComObj.put("airSiteCd"   , schAirFareChangeSearchVo.getSaleSiteCd());
		bizComObj.put("ptnCd"       , schAirFareChangeSearchVo.getPtnCd());
		bizComObj.put("unfyMemNum"  , null);
		bizComObj.put("staffUsrId"  , null);
		bizComObj.put("saleCurrency", "KRW");
		bizComObj.put("saleCountry" , "KR");
		bizComObj.put("langCode"    , "KOKR");
		bizComObj.put("isDebug"     , schAirFareChangeSearchVo.getRuleExprCntlDebugFg());
		bizComObj.put("maxLimitRows", -1);
		
 	    //itnrList Setting
		if(schAirFareChangeSearchVo.getDepPlcCd1() !=null && schAirFareChangeSearchVo.getTrvlDepDt1().length()==8 ){
			itnrDepObj1.put("depPlcCd"  ,schAirFareChangeSearchVo.getDepPlcCd1());
			itnrDepObj1.put("depPlcDvCd",schAirFareChangeSearchVo.getDepPlcDvCd1());
			itnrDepObj1.put("arrPlcCd"  ,schAirFareChangeSearchVo.getArrPlcCd1());
			itnrDepObj1.put("arrPlcDvCd",schAirFareChangeSearchVo.getArrPlcDvCd1());
			itnrDepObj1.put("depDt"     ,schAirFareChangeSearchVo.getTrvlDepDt1());  
			itnrList.add(itnrDepObj1); 
		}
		
		if(schAirFareChangeSearchVo.getDepPlcCd2() !=null && schAirFareChangeSearchVo.getTrvlDepDt2().length()==8  ){
			itnrDepObj2.put("depPlcCd"  ,schAirFareChangeSearchVo.getDepPlcCd2());
			itnrDepObj2.put("depPlcDvCd",schAirFareChangeSearchVo.getDepPlcDvCd2());
			itnrDepObj2.put("arrPlcCd"  ,schAirFareChangeSearchVo.getArrPlcCd2());
			itnrDepObj2.put("arrPlcDvCd",schAirFareChangeSearchVo.getArrPlcDvCd2());
			itnrDepObj2.put("depDt"     ,schAirFareChangeSearchVo.getTrvlDepDt2());
			itnrList.add(itnrDepObj2); 
		}
		
		if(schAirFareChangeSearchVo.getDepPlcCd3() !=null && schAirFareChangeSearchVo.getTrvlDepDt3().length()==8  ){
			itnrDepObj3.put("depPlcCd"  ,schAirFareChangeSearchVo.getDepPlcCd3());
			itnrDepObj3.put("depPlcDvCd",schAirFareChangeSearchVo.getDepPlcDvCd3());
			itnrDepObj3.put("arrPlcCd"  ,schAirFareChangeSearchVo.getArrPlcCd3());
			itnrDepObj3.put("arrPlcDvCd",schAirFareChangeSearchVo.getArrPlcDvCd3());
			itnrDepObj3.put("depDt"     ,schAirFareChangeSearchVo.getTrvlDepDt3());
			itnrList.add(itnrDepObj3); 
		}
		
		if(schAirFareChangeSearchVo.getDepPlcCd4() !=null && schAirFareChangeSearchVo.getTrvlDepDt4().length()==8 ){
			itnrDepObj4.put("depPlcCd"  ,schAirFareChangeSearchVo.getDepPlcCd4());
			itnrDepObj4.put("depPlcDvCd",schAirFareChangeSearchVo.getDepPlcDvCd4());
			itnrDepObj4.put("arrPlcCd"  ,schAirFareChangeSearchVo.getArrPlcCd4());
			itnrDepObj4.put("arrPlcDvCd",schAirFareChangeSearchVo.getArrPlcDvCd4());
			itnrDepObj4.put("depDt"     ,schAirFareChangeSearchVo.getTrvlDepDt4());
			itnrList.add(itnrDepObj4);  
		}
		
		if(schAirFareChangeSearchVo.getDepPlcCd5() != null && schAirFareChangeSearchVo.getTrvlDepDt5().length()==8  ){
			itnrDepObj5.put("depPlcCd"  ,schAirFareChangeSearchVo.getDepPlcCd5());
			itnrDepObj5.put("depPlcDvCd",schAirFareChangeSearchVo.getDepPlcDvCd5());
			itnrDepObj5.put("arrPlcCd"  ,schAirFareChangeSearchVo.getArrPlcCd5());
			itnrDepObj5.put("arrPlcDvCd",schAirFareChangeSearchVo.getArrPlcDvCd5());
			itnrDepObj5.put("depDt"     ,schAirFareChangeSearchVo.getTrvlDepDt5());
			itnrList.add(itnrDepObj5);
		}
		
		if(schAirFareChangeSearchVo.getDepPlcCd6() != null && schAirFareChangeSearchVo.getTrvlDepDt6().length()==8 ){
			itnrDepObj6.put("depPlcCd"  ,schAirFareChangeSearchVo.getDepPlcCd6());
			itnrDepObj6.put("depPlcDvCd",schAirFareChangeSearchVo.getDepPlcDvCd6());
			itnrDepObj6.put("arrPlcCd"  ,schAirFareChangeSearchVo.getArrPlcCd6());
			itnrDepObj6.put("arrPlcDvCd",schAirFareChangeSearchVo.getArrPlcDvCd6());
			itnrDepObj6.put("depDt"     ,schAirFareChangeSearchVo.getTrvlDepDt6());
			itnrList.add(itnrDepObj6);
		}	

		//psngrCntLst Setting 
		if(schAirFareChangeSearchVo.getAgeDvCd1() !=null ){
			psngrObj1.put("ageDvCd" , schAirFareChangeSearchVo.getAgeDvCd1());
			psngrObj1.put("psngrCnt", schAirFareChangeSearchVo.getPsngrCnt1());
			psngrList.add(psngrObj1);
		}
		
		if(schAirFareChangeSearchVo.getAgeDvCd2() !=null ){
			psngrObj2.put("ageDvCd" , schAirFareChangeSearchVo.getAgeDvCd2());
			psngrObj2.put("psngrCnt", schAirFareChangeSearchVo.getPsngrCnt2());	
			psngrList.add(psngrObj2);
		}
		
		if(schAirFareChangeSearchVo.getAgeDvCd3() !=null ){ 
			psngrObj3.put("ageDvCd" , schAirFareChangeSearchVo.getAgeDvCd3());
			psngrObj3.put("psngrCnt", schAirFareChangeSearchVo.getPsngrCnt3());	
			psngrList.add(psngrObj3);
		} 
		
		//API Request Json setting
		reqObj.put("bizCom"     , bizComObj);
		reqObj.put("itnrLst"    , itnrList );
		reqObj.put("psngrCntLst", psngrList);

		String jsonString = CoreUtil.getJsonUtil().toJson(reqObj); 
		return jsonString;
	}

	private String callApiService(String requestBody){
		try {
			final Header mciHeader = new Header();
			mciHeader.setJobSysCd("P_AIR_A0SLE"); //업무시스템코드
			mciHeader.setPupsInttCd("BP_AIR"); //목적기관코드
			mciHeader.setFltxTycd("4280"); //전문유형코드
			mciHeader.setTrncDvcd("550"); // 처리구분코드
			mciHeader.setRspnTrnrMdcd("R"); // 응답전달방식코드
			mciHeader.setSvcDmnInfo(RestServiceClient.HANATOUR_AIR_API); //서비스도메인정보
			mciHeader.setSvcTrncId(RealtimeConst.SCH_AIR_FARE_API_URL); //서비스거래ID

			return restServiceClient.sendWithMciHeaderToString(ServiceEndpoint.ENDPOINT_API, RealtimeConst.SCH_AIR_FARE_API_URL, mciHeader, requestBody);
		} catch (ApiException e) {
			logger.error("schStdAirFar fail", e);
			throw new BusinessException("ESVAIR000003", "[항공API]");
		}
	}
	
	private String callApiServiceForRuleTest(String url, String requestBody){
		try {
			final Header mciHeader = new Header();
			mciHeader.setJobSysCd("P_AIR_A0SLE"); //업무시스템코드
			mciHeader.setPupsInttCd("BP_AIR"); //목적기관코드
			mciHeader.setFltxTycd("4280"); //전문유형코드
			mciHeader.setTrncDvcd("550"); // 처리구분코드
			mciHeader.setRspnTrnrMdcd("R"); // 응답전달방식코드
			mciHeader.setSvcDmnInfo(RestServiceClient.HANATOUR_AIR_API); //서비스도메인정보
			mciHeader.setSvcTrncId(url); //서비스거래ID

			return restServiceClient.sendWithMciHeaderToString(ServiceEndpoint.ENDPOINT_API, url, mciHeader, requestBody);
		} catch (ApiException e) {
			logger.error("schStdAirFar fail", e);
			throw new BusinessException("ESVAIR000003", "[항공API]");
		}
	}
	
	/**
	 * 운임조회 > 미리보기 - 룰셋 테스트용 inVo 생성(Json구문 편집함으로)
	 */
	@Override
	@SuppressWarnings({"rawtypes", "unchecked", "unused"})
	@ServiceMapping(value = "/air/ols/supply/realtime/cbc/schairfare/getAirFareRuleSetTest/v1.00", method = RequestMethod.POST)
	public SchAirFareRuleSetTestOutVo getAirFareRuleSetTest(SchAirFareRuleSetTestInVo schAirFareRuleSetTestInVo) {
		String sRuleSetTestUrl = schAirFareRuleSetTestInVo.getRuleSetTestUrl();
		String sInVoJson       = schAirFareRuleSetTestInVo.getInVoJson();
	
		String returnStr = this.callApiServiceForRuleTest(sRuleSetTestUrl, sInVoJson);
		
		SchAirFareRuleSetTestOutVo schAirFareRuleSetTestOutVo = new SchAirFareRuleSetTestOutVo();
		schAirFareRuleSetTestOutVo.setResultJson(returnStr);
		
		return schAirFareRuleSetTestOutVo;
	}

	/**
	 * 운임조회 > 미리보기 - 룰셋 테스트용 inVo 생성(Json구문 편집함으로)
	 */
	@Override
	@SuppressWarnings({"rawtypes", "unchecked", "unused"})
	@ServiceMapping(value = "/air/ols/supply/realtime/cbc/schairfare/getAirFareRuleSetUseMethod/v1.00", method = RequestMethod.POST)
	public SchAirFareRuleSetTestOutVo getAirFareRuleSetUseMethod(SchAirFareRuleSetTestInVo schAirFareRuleSetTestInVo) {

		String sRuleSetTypeId  = schAirFareRuleSetTestInVo.getRuleSetTypeId();
//		String sRuleSetTypeNm  = schAirFareRuleSetTestInVo.getRuleSetTypeNm();
		String sRuleSetTestUrl = schAirFareRuleSetTestInVo.getRuleSetTestUrl();
		String sInVoJson       = schAirFareRuleSetTestInVo.getInVoJson();
		String rslStr = "";
		if("01".equals(sRuleSetTypeId)) {
			//-------------------------------
			// 01 : 수수료 - /air/api/rule/FarConfRule/rltmfeerulemgmt/getCalcFeeInfo/v1.00
			//-------------------------------
			RltmFeeRuleInVo inVo = CoreUtil.getJsonUtil().fromJson(sInVoJson, RltmFeeRuleInVo.class);
			
			WorkerContext context = WorkerContext.of(inVo);
			
			RltmAplFeeDtlVo outVo = rltmFeeRuleTs.apply(WorkerContextResult.from(context, inVo));
			
			rslStr = CoreUtil.getJsonUtil().toJson(outVo);
		} else if("04".equals(sRuleSetTypeId)) {
			//-------------------------------
			// 04 : 결제TL - /air/api/rule/FarConfRule/rltmpaytlapl/getAblPayTl/v1.00
			//-------------------------------
			RltmRqPayTlAplVo inVo = CoreUtil.getJsonUtil().fromJson(sInVoJson, RltmRqPayTlAplVo.class);
			
			WorkerContext context = WorkerContext.of(inVo);
			
			RltmRsPayTlAplVo outVo = rltmPayTlAplTs.apply(WorkerContextResult.from(context, inVo));
			
			rslStr = CoreUtil.getJsonUtil().toJson(outVo);
		} else if("02".equals(sRuleSetTypeId)) {
			//-------------------------------
			// 02 : 공급사커미션 - /air/api/rule/FarConfRule/rltmcmsnmgmt/getSuplCmsn/v1.00
			//-------------------------------
			RltmSuplCmsnCalcInVo inVo = CoreUtil.getJsonUtil().fromJson(sInVoJson, RltmSuplCmsnCalcInVo.class);
			
			WorkerContext context = WorkerContext.of(inVo);
			
			RltmSuplCmsnCalcOutVo outVo = rltmSuplCmsnTs.apply(WorkerContextResult.from(context, inVo));
			
			rslStr = CoreUtil.getJsonUtil().toJson(outVo);
		} else if("05".equals(sRuleSetTypeId)) {
			//-------------------------------
			// 05 : 대리점커미션 - /air/api/rule/FarConfRule/rltmcmsnmgmt/getAgtCmsn/v1.00
			//-------------------------------
			RltmAgtCmsnCalcInVo inVo = CoreUtil.getJsonUtil().fromJson(sInVoJson, RltmAgtCmsnCalcInVo.class);
			
			WorkerContext context = WorkerContext.of(inVo);
			
			RltmAgtCmsnCalcOutVo outVo = rltmAgtCmsnTs.apply(WorkerContextResult.from(context, inVo));
			
			rslStr = CoreUtil.getJsonUtil().toJson(outVo);
		} else if("03".equals(sRuleSetTypeId)) {
			//-------------------------------
			// 03 : 자동발권 - /air/api/rule/farconfrule/rltmatmtisuepsblyn/getatmtisuepsbl/v1.00
			//-------------------------------
			RltmAtmtIsuePsblVo inVo = CoreUtil.getJsonUtil().fromJson(sInVoJson, RltmAtmtIsuePsblVo.class);
			
			WorkerContext context = WorkerContext.of(inVo);
			
			RltmAtmtIsuePsblOutVo outVo = rltmAtmtIsuePsblYnRuleTs.apply(WorkerContextResult.from(context, inVo));
			
			rslStr = CoreUtil.getJsonUtil().toJson(outVo);
		} else if("06".equals(sRuleSetTypeId)) {
			//-------------------------------
			// 06 : 운임규정 - /air/api/rule/FarConfRule/rltmrulemgmt/getFarRulCont/v1.00
			//-------------------------------
			RltmRulDtlInVo inVo = CoreUtil.getJsonUtil().fromJson(sInVoJson, RltmRulDtlInVo.class);
			
			WorkerContext context = WorkerContext.of(inVo);
			
			RltmRulDtlOutVo outVo = rltmFareRuleTs.apply(WorkerContextResult.from(context, inVo));
			
			rslStr = CoreUtil.getJsonUtil().toJson(outVo);
		} else if("07".equals(sRuleSetTypeId)) {
			AirSrchRsltApiVo airSrchRsltApiVo = new AirSrchRsltApiVo();

			try {
				AirSrchRsltApiVo responseData =  CoreUtil.getJsonUtil().fromJson(sInVoJson, AirSrchRsltApiVo.class);

				// API호출용 조회조건 설정
				BizComVo bizCom = new BizComVo();
				bizCom.setSaleChnlCd("HUB");//판매채널코드
				bizCom.setResPathCd("");//예약경로코드
				bizCom.setAirSiteCd(responseData.getSaleSiteCd());//판매사이트코드
				bizCom.setSaleCurrency("KRW");
				bizCom.setSaleCountry("KR");
				bizCom.setLangCode("KOKR");

				airSrchRsltApiVo.setBizCom(bizCom);

				airSrchRsltApiVo.setCacheSearchYn(responseData.getCacheSearchYn());
				airSrchRsltApiVo.setFareId(responseData.getFareId());

				// 운임검색 API 호출
				final String path = "/air/api/service/sch/cbc/schAirApi/schAirFarFareFamily/v1.00";
				final Header mciHeader = new Header();
				mciHeader.setTrncOcrnSysCd("BP_AIR");//거래발생시스템코드
				mciHeader.setJobSysCd("P_AIR_A0APS"); //업무시스템코드
				mciHeader.setPupsInttCd("BP_AAI"); //목적기관코드
				mciHeader.setFltxTycd("6500"); //전문유형코드
				mciHeader.setTrncDvcd("1001");    //거래구분코드
				mciHeader.setRspnTrnrMdcd("M"); //응답전달방식코드
				mciHeader.setSvcDmnInfo(RestServiceClient.HANATOUR_AIR_API); //서비스도메인정보
				mciHeader.setSvcTrncId(path); //서비스거래ID
				final JsonNode result = restServiceClient.sendWithMciHeaderToJsonNode(ServiceEndpoint.ENDPOINT_API, path, mciHeader, airSrchRsltApiVo);
				rslStr = String.valueOf(result);
			} catch (Exception e) {
				e.printStackTrace();
			}

		}
		
		SchAirFareRuleSetTestOutVo schAirFareRuleSetTestOutVo = new SchAirFareRuleSetTestOutVo();
		schAirFareRuleSetTestOutVo.setResultJson(rslStr);
		
		return schAirFareRuleSetTestOutVo;
	}
}