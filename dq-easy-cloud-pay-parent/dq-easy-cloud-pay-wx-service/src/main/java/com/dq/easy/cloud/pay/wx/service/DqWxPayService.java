package com.dq.easy.cloud.pay.wx.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.dq.easy.cloud.module.basic.constant.error.DqBaseErrorCodeEnum;
import com.dq.easy.cloud.module.basic.utils.DqBaseUtils;
import com.dq.easy.cloud.module.common.date.utils.DqDateFormatUtils;
import com.dq.easy.cloud.module.common.date.utils.DqDateUtils;
import com.dq.easy.cloud.module.common.http.constant.DqHttpConstant.DqMethodType;
import com.dq.easy.cloud.module.common.http.pojo.dto.DqHttpConfigStorageDTO;
import com.dq.easy.cloud.module.common.log.utils.DqLogUtils;
import com.dq.easy.cloud.module.common.map.utils.DqMapUtils;
import com.dq.easy.cloud.module.common.qrcode.utils.DqQrCodeUtil;
import com.dq.easy.cloud.module.common.sign.utils.DqSignUtils;
import com.dq.easy.cloud.module.common.string.constant.DqStringConstant.DqSymbol;
import com.dq.easy.cloud.module.common.string.utils.DqStringUtils;
import com.dq.easy.cloud.module.common.xml.utils.DqXMLUtils;
import com.dq.easy.cloud.module.exception.bo.DqBaseBusinessException;
import com.dq.easy.cloud.pay.common.payment.config.dto.DqPayConfigStorageInf;
import com.dq.easy.cloud.pay.common.payment.constant.DqPayErrorCodeEnum;
import com.dq.easy.cloud.pay.common.payment.constant.DqWxPayConstant.DqWxPayKey;
import com.dq.easy.cloud.pay.common.payment.constant.DqWxPayConstant.DqWxPayValue;
import com.dq.easy.cloud.pay.common.payment.pojo.dto.DqPayOrderDTO;
import com.dq.easy.cloud.pay.common.payment.pojo.query.DqOrderAbstractQuery;
import com.dq.easy.cloud.pay.common.payment.service.DqPayServiceAbstract;
import com.dq.easy.cloud.pay.common.paymessage.pojo.dto.DqPayMessageDTO;
import com.dq.easy.cloud.pay.common.paymessage.pojo.dto.DqPayOutMessageDTO;
import com.dq.easy.cloud.pay.common.refund.dto.DqRefundOrderAbstractDTO;
import com.dq.easy.cloud.pay.common.transaction.inf.DqTransactionType;
import com.dq.easy.cloud.pay.common.transaction.pojo.dto.DqTransferOrderDTO;
import com.dq.easy.cloud.pay.wx.pojo.bo.DqWxTransactionType;
import com.dq.easy.cloud.pay.wx.pojo.query.DqWxOrderQuery;

/**
 * 微信支付服务
 */
public class DqWxPayService extends DqPayServiceAbstract {
	private final Logger LOG = LoggerFactory.getLogger(this.getClass());

	/** 提供默认构造--只是为了反射用 */
	public DqWxPayService() {
		
	}
	/**
	 * 创建支付服务
	 * 
	 * @param payConfigStorage
	 *            微信对应的支付配置
	 */
	public DqWxPayService(DqPayConfigStorageInf payConfigStorage) {
		super(payConfigStorage);
	}

	/**
	 * 创建支付服务
	 * 
	 * @param payConfigStorage
	 *            微信对应的支付配置
	 * @param configStorage
	 *            微信对应的网络配置，包含代理配置、ssl证书配置
	 */
	public DqWxPayService(DqPayConfigStorageInf payConfigStorage, DqHttpConfigStorageDTO configStorage) {
		super(payConfigStorage, configStorage);
	}

	/**
	 * 回调校验
	 *
	 * @param params
	 *            回调回来的参数集
	 * @return 签名校验 true通过
	 */
	@Override
	public boolean verify(Map<String, Object> params) {
//		校验是否成功
		if (DqWxPayValue.isNotSUCCESS(params.get(DqWxPayKey.RETURN__CODE_KEY))){
			DqLogUtils.debug(String.format("微信支付异常：return_code=%s,参数集=%s", params.get(DqWxPayKey.RETURN__CODE_KEY)), params, LOG);
			return false;
		}
//		校验签名信息是否为空
		if (DqBaseUtils.isNull(params.get(DqWxPayKey.SIGN_KEY))) {
			DqLogUtils.debug("微信支付异常：签名为空！out_trade_no=" + params.get("out_trade_no"), params, LOG);
			return false;
		}
		try {
			return signVerify(params, DqMapUtils.getString(params, DqWxPayKey.SIGN_KEY)) && verifySource(DqMapUtils.getString(params, DqWxPayKey.OUT__TRADE__NO_KEY));
		} catch (Exception e) {
			DqLogUtils.error("签名校验异常", e, LOG);
		}
		return false;
	}

	/**
	 * 微信是否也需要再次校验来源，进行订单查询
	 *
	 * @param id
	 *            商户单号
	 * @return true通过
	 */
	@Override
	public boolean verifySource(String id) {
		return true;
	}

	/**
	 * 根据反馈回来的信息，生成签名结果
	 *
	 * @param params
	 *            通知返回来的参数数组
	 * @param sign
	 *            比对的签名结果
	 * @return 生成的签名结果
	 */
	@Override
	public boolean signVerify(Map<String, Object> params, String sign) {
		return DqSignUtils.valueOf(payConfigStorage.getSignType()).verify(params, sign,
				"&key=" + payConfigStorage.getKeyPrivate(), payConfigStorage.getInputCharset());
	}

	/**
	 * 微信统一下单接口
	 *
	 * @param order
	 *            支付订单集
	 * @return 下单结果
	 */
	public Map<String, Object> unifiedOrder(DqPayOrderDTO order) {
		//// 统一下单
		Map<String, Object> parameters = getPublicParameters();
		parameters.put(DqWxPayKey.BODY_KEY, order.getSubject());// 购买支付信息
		parameters.put(DqWxPayKey.OUT__TRADE__NO_KEY, order.getOutTradeNo());// 订单号
		String spbillCreateIp = DqStringUtils.isEmpty(order.getSpbillCreateIp()) ? DqWxPayValue.SPBILL_CREATE_IP_DEFAULT : order.getSpbillCreateIp();
		parameters.put(DqWxPayKey.SPBILL__CREATE__IP_KEY, spbillCreateIp);
		parameters.put(DqWxPayKey.TOTAL__FEE_KEY, order.getPriceOfCent());// 总金额单位为分
		parameters.put(DqWxPayKey.ATTACH_KEY, order.getBody());
		parameters.put(DqWxPayKey.NOTIFY__URL_KEY, payConfigStorage.getNotifyUrl());
		parameters.put(DqWxPayKey.TRADE__TYPE_KEY, order.getTransactionType().getType());
		((DqWxTransactionType) order.getTransactionType()).setAttribute(parameters, order);

		String sign = createSign(DqSignUtils.parameterText(parameters), payConfigStorage.getInputCharset());
		parameters.put(DqWxPayKey.SIGN_KEY, sign);

		String requestXML = DqXMLUtils.getXmlStrFromMap(parameters);
		DqLogUtils.debug("请求的requestXML", requestXML, LOG);
		// 调起支付的参数列表
		@SuppressWarnings("unchecked")
		Map<String, Object> result = requestTemplate.postForObject(getUrl(order.getTransactionType()), requestXML, HashMap.class);
		
//		不成功抛出异常
		if (DqWxPayValue.isNotSUCCESS(result.get(DqWxPayKey.RETURN__CODE_KEY))) {
			DqLogUtils.error("订单创建失败", result, LOG);
			throw DqBaseBusinessException.newInstance(DqMapUtils.getString(result, DqWxPayKey.RETURN__CODE_KEY),
					DqMapUtils.getString(result, DqWxPayKey.RETURN__MSG_KEY));
		}
		return result;
	}

	/**
	 * 
	 * <p>
	 * 微信公众号支付
	 * </p>
	 *
	 * <pre>
	 *     所需参数示例及其说明
	 *     参数名称 : 示例值 : 说明 : 是否必须
	 *     dqPayOrderDTO.subject : 支付洛 : 支付主题 : 是
	 *     dqPayOrderDTO.body : 摘要 : 支付主简述: 是
	 *     dqPayOrderDTO.price : 0.01 : 支付价格 : 是
	 *     dqPayOrderDTO.outTradeNo : PON2017152453125487 : 商户订单号 : 是
	 *     dqPayOrderDTO.DqWxTransactionType : DqWxTransactionType.JSAPI : 支付价格 : 交易类型
	 * </pre>
	 *
	 * @param dqPayOrderDTO
	 * @return DqBaseServiceResult
	 * @author daiqi
	 * 创建时间    2018年2月24日 下午2:19:55
	 */
	@Override
	public Map<String, Object> orderInfo(DqPayOrderDTO order) {

		//// 统一下单
		Map<String, Object> result = unifiedOrder(order);

		DqTransactionType transactionType = order.getTransactionType();
		// 如果是扫码支付或者刷卡付无需处理，直接返回
		if (DqWxTransactionType.isNATIVE(transactionType)
				|| DqWxTransactionType.isMICROPAY(transactionType)
				|| DqWxTransactionType.isMWEB(transactionType)) {
			return result;
		}

		SortedMap<String, Object> params = new TreeMap<String, Object>();

		if (DqWxTransactionType.isJSAPI(transactionType)) {
			params.put(DqWxPayKey.SIGN_TYPE_KEY, payConfigStorage.getSignType());
			params.put(DqWxPayKey.APP_ID_KEY, payConfigStorage.getAppid());
			// 此处必须为String类型 否则调用jsapi时会提示找不到timeStamp
			params.put(DqWxPayKey.TIME_STAMP_KEY, DqDateUtils.getCurrentTimeSecStr());
			params.put(DqWxPayKey.NONCE_STR_KEY, result.get(DqWxPayKey.NONCE__STR_KEY));
			params.put(DqWxPayKey.PACKAGE_KEY, DqWxPayKey.PREPAY__ID_KEY + "=" + result.get(DqWxPayKey.PREPAY__ID_KEY));
		} else if (DqWxTransactionType.isAPP(transactionType)) {
			params.put(DqWxPayKey.PARTNERID_KEY, payConfigStorage.getPid());
			params.put(DqWxPayKey.APPID_KEY, payConfigStorage.getAppid());
			params.put(DqWxPayKey.PREPAYID_KEY, result.get(DqWxPayKey.PREPAY__ID_KEY));
			params.put(DqWxPayKey.TIMESTAMP_KEY, DqDateUtils.getCurrentTimeSecStr());
			params.put(DqWxPayKey.NONCE_STR_KEY, result.get(DqWxPayKey.NONCE__STR_KEY));
			params.put(DqWxPayKey.PACKAGE_KEY, DqWxPayValue.APP_PACKAGE_VALUE);
		}
		String paySign = createSign(DqSignUtils.parameterText(params), payConfigStorage.getInputCharset());
		params.put(DqWxPayKey.SIGN_KEY, paySign);
		return params;

	}

	/**
	 * 签名
	 *
	 * @param content
	 *            需要签名的内容 不包含key
	 * @param characterEncoding
	 *            字符编码
	 * @return 签名结果
	 */
	@Override
	public String createSign(String content, String characterEncoding) {
		
		return DqSignUtils.valueOf(DqStringUtils.upperCase(payConfigStorage.getSignType()))
				.createSign(content, "&key=" + payConfigStorage.getKeyPrivate(), characterEncoding).toUpperCase();
	}

	/**
	 * 将请求参数或者请求流转化为 Map
	 *
	 * @param parameterMap
	 *            请求参数
	 * @param is
	 *            请求流
	 * @return 获得回调的请求参数
	 */
	@Override
	public Map<String, Object> getParameterToMap(Map<String, String[]> parameterMap, InputStream is) {
		TreeMap<String, Object> map = new TreeMap<>();
		try {
			return DqXMLUtils.getMapFromInputStream(is, map);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 获取输出消息，用户返回给支付端
	 *
	 * @param code
	 *            状态
	 * @param message
	 *            消息
	 * @return 返回输出消息
	 */
	@Override
	public DqPayOutMessageDTO getPayOutMessage(String code, String message) {
		return DqPayOutMessageDTO.XML().code(code.toUpperCase()).content(message).build();
	}

	/**
	 * 获取成功输出消息，用户返回给支付端 主要用于拦截器中返回
	 *
	 * @param payMessage
	 *            支付回调消息
	 * @return 返回输出消息
	 */
	@Override
	public DqPayOutMessageDTO successPayOutMessage(DqPayMessageDTO payMessage) {
		return DqPayOutMessageDTO.XML().code(DqWxPayValue.SUCCESS).content("成功").build();
	}

	/**
	 * 获取输出消息，用户返回给支付端, 针对于web端
	 *
	 * @param orderInfo
	 *            发起支付的订单信息
	 * @param method
	 *            请求方式 "post" "get",
	 * @return 获取输出消息，用户返回给支付端, 针对于web端
	 * @see DqMethodType 请求类型
	 */
	@SuppressWarnings("deprecation")
	@Override
	public String buildRequest(Map<String, Object> orderInfo, DqMethodType method) {
		if (DqWxPayValue.isNotSUCCESS(orderInfo.get(DqWxPayKey.RETURN__CODE_KEY))) {
			throw DqBaseBusinessException.newInstance(DqMapUtils.getString(orderInfo, DqWxPayKey.RETURN__CODE_KEY),DqMapUtils.getString(orderInfo, DqWxPayKey.RETURN__MSG_KEY));
		}
		if (DqWxTransactionType.isMWEB(DqMapUtils.getString(orderInfo, DqWxPayKey.TRADE__TYPE_KEY))) {
//			需要被格式化得字符串
			String needBeFormatStr = "<script type=\"text/javascript\">location.href=\"%s%s\"</script>";
			String formatReturnUrl = DqStringUtils.EMPTY;
			
			if (DqStringUtils.isNotEmpty(payConfigStorage.getReturnUrl())){
				formatReturnUrl = "&redirect_url=" + URLEncoder.encode(payConfigStorage.getReturnUrl());
			}
			return String.format(needBeFormatStr, orderInfo.get(DqWxPayKey.MWEB__URL_KEY), formatReturnUrl);
		}
		throw new UnsupportedOperationException();

	}

	/**
	 * 获取输出二维码，用户返回给支付端,
	 *
	 * @param order
	 *            发起支付的订单信息
	 * @return 返回图片信息，支付时需要的
	 */
	@Override
	public BufferedImage generatePayQrCode(DqPayOrderDTO order) {
		Map<String, Object> orderInfo = orderInfo(order);
		DqLogUtils.info("二维码对象", orderInfo, LOG);
		// 获取对应的支付账户操作工具（可根据账户id）
		if (DqWxPayValue.isNotSUCCESS(orderInfo.get(DqWxPayKey.RESULT__CODE_KEY))) {
			throw DqBaseBusinessException.newInstance(DqMapUtils.getString(orderInfo, DqWxPayKey.RESULT__CODE_KEY),
					DqMapUtils.getString(orderInfo, DqWxPayKey.ERR__CODE_KEY)).buildExceptionDetail(orderInfo);
		}
		return DqQrCodeUtil.writeInfoToJpgBuff(DqMapUtils.getString(orderInfo, DqWxPayKey.CODE__URL_KEY));
	}

	/**
	 * 刷卡付,pos主动扫码付款
	 *
	 * @param order
	 *            发起支付的订单信息
	 * @return 返回支付结果
	 */
	@Override
	public Map<String, Object> microPay(DqPayOrderDTO order) {
		return orderInfo(order);
	}

	/**
	 * 交易查询接口
	 *
	 * @param transactionId
	 *            微信支付平台订单号
	 * @param outTradeNo
	 *            商户单号
	 * @return 返回查询回来的结果集，支付方原值返回
	 */
	@Override
	public Map<String, Object> queryPayResult(String transactionId, String outTradeNo) {
		return secondaryInterface(transactionId, outTradeNo, DqWxTransactionType.QUERY);
	}

	/**
	 * 交易关闭接口
	 *
	 * @param transactionId
	 *            支付平台订单号
	 * @param outTradeNo
	 *            商户单号
	 * @return 返回支付方交易关闭后的结果
	 */
	@Override
	public Map<String, Object> close(String transactionId, String outTradeNo) {

		return secondaryInterface(transactionId, outTradeNo, DqWxTransactionType.CLOSE);
	}

	/**
	 * 申请退款接口
	 *
	 * @param refundOrder
	 *            退款订单信息
	 * @return 返回支付方申请退款后的结果
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> refund(DqRefundOrderAbstractDTO refundOrder) {
		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();
		if (DqStringUtils.isNotEmpty(refundOrder.getTradeNo())) {
			parameters.put(DqWxPayKey.TRANSACTION__ID_KEY, refundOrder.getTradeNo());
		} else {
			parameters.put(DqWxPayKey.OUT__TRADE__NO_KEY, refundOrder.getOutTradeNo());
		}
		parameters.put(DqWxPayKey.OUT__REFUND__NO_KEY, refundOrder.getRefundNo());
		parameters.put(DqWxPayKey.TOTAL__FEE_KEY, refundOrder.getTotalAmountOfCent());
		parameters.put(DqWxPayKey.REFUND__FEE_KEY, refundOrder.getRefundAmountOfCent());
		parameters.put(DqWxPayKey.OP__USER__ID_KEY, payConfigStorage.getPid());
		parameters.put(DqWxPayKey.NONCE__STR_KEY, String.valueOf(System.currentTimeMillis()));
		// 设置签名
		setSign(parameters);
		Map<String, Object> retMap = requestTemplate.postForObject(getUrl(DqWxTransactionType.REFUND),
				DqXMLUtils.getXmlStrFromMap(parameters), HashMap.class);
		if (DqMapUtils.isEmpty(retMap)) {
			throw DqBaseBusinessException.newInstance(DqPayErrorCodeEnum.PAY_FAILURE).buildExceptionDetail(retMap);
		}
//		Map<String, Object> retMap = DqJSONUtils.parseObject(wxPayRefund(refundOrder), HashMap.class);
		return retMap;
	}
	/**
     * @Author: HONGLINCHEN
     * @Description:微信退款   注意：：微信金额的单位是分 所以这里要X100 转成int是因为 退款的时候不能有小数点
     * @param merchantNumber 商户这边的订单号
     * @param wxTransactionNumber 微信那边的交易单号
     * @param totalFee 订单的金额
     * @Date: 2017-9-12 11:18
     */
    @SuppressWarnings("deprecation")
	public Object wxPayRefund(DqRefundOrderAbstractDTO refundOrder) {
        try{
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            FileInputStream instream = new FileInputStream(new File("E:/tools/wx_pay/cert/apiclient_cert.p12"));
            try {
                keyStore.load(instream, payConfigStorage.getPid().toCharArray());
            }finally {
                instream.close();
            }
            // Trust own CA and all self-signed certs
            SSLContext sslcontext = SSLContexts.custom().loadKeyMaterial(keyStore, payConfigStorage.getPid().toCharArray()).build();
            // Allow TLSv1 protocol only
            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                    sslcontext, new String[] { "TLSv1" }, null,
                    SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
            CloseableHttpClient httpclient = HttpClients.custom()
                    .setSSLSocketFactory(sslsf).build();
            // HttpGet httpget = new
            // HttpGet("https://api.mch.weixin.qq.com/secapi/pay/refund");
            HttpPost httppost = new HttpPost("https://api.mch.weixin.qq.com/secapi/pay/refund");
            //微信金额的单位是分 所以这里要X100 转成int是因为 退款的时候不能有小数点
//            String xml = WXPayUtil.wxPayRefund(merchantNumber,wxTransactionNumber,String.valueOf((int)(totalFee*100)));
         // 获取公共参数
    		Map<String, Object> parameters = getPublicParameters();
    		if (DqStringUtils.isNotEmpty(refundOrder.getTradeNo())) {
    			parameters.put(DqWxPayKey.TRANSACTION__ID_KEY, refundOrder.getTradeNo());
    		} else {
    			parameters.put(DqWxPayKey.OUT__TRADE__NO_KEY, refundOrder.getOutTradeNo());
    		}
    		parameters.put(DqWxPayKey.OUT__REFUND__NO_KEY, refundOrder.getRefundNo());
    		parameters.put(DqWxPayKey.TOTAL__FEE_KEY, refundOrder.getTotalAmountOfCent());
    		parameters.put(DqWxPayKey.REFUND__FEE_KEY, refundOrder.getRefundAmount());
    		parameters.put(DqWxPayKey.OP__USER__ID_KEY, payConfigStorage.getPid());
    		parameters.put(DqWxPayKey.NONCE__STR_KEY, String.valueOf(System.currentTimeMillis()));
    		// 设置签名
    		setSign(parameters);
            String xml = DqXMLUtils.getXmlStrFromMap(parameters);
            try {
                StringEntity se = new StringEntity(xml);
                httppost.setEntity(se);
                System.out.println("executing request" + httppost.getRequestLine());
                CloseableHttpResponse responseEntry = httpclient.execute(httppost);
                try {
                    HttpEntity entity = responseEntry.getEntity();
                    System.out.println(responseEntry.getStatusLine());
                    if (entity != null) {
                    	return DqXMLUtils.getMapFromInputStream(entity.getContent());
                    }
                    EntityUtils.consume(entity);
                }
                finally {
                    responseEntry.close();
                }
            }
            finally {
                httpclient.close();
            }
            return null;
        }catch(Exception e){
            e.printStackTrace();
            JSONObject result = new JSONObject();
            result.put("status","error");
            result.put("msg",e.getMessage());
            return result;
        }
    }

	/**
	 * 查询退款
	 *
	 * @param transactionId
	 *            支付平台订单号
	 * @param outTradeNo
	 *            商户单号
	 * @return 返回支付方查询退款后的结果
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> queryRefundResult(DqOrderAbstractQuery dqOrderQuery) {
		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();
		if (DqStringUtils.isNotEmpty(dqOrderQuery.getOutTradeNo())) {
			parameters.put(DqWxPayKey.OUT__TRADE__NO_KEY, dqOrderQuery.getOutTradeNo());
		} else {
			parameters.put(DqWxPayKey.TRANSACTION__ID_KEY, dqOrderQuery.getTradeNo());
		}
		parameters.put(DqWxPayKey.OUT__REFUND__NO_KEY, dqOrderQuery.getRefundTradeNo());
		// 设置签名
		setSign(parameters);
		return requestTemplate.postForObject(getUrl(DqWxTransactionType.REFUNDQUERY), DqXMLUtils.getXmlStrFromMap(parameters),
				HashMap.class);
	}

	/**
	 * 目前只支持日账单
	 *
	 * @param billDate
	 *             账单时间：日账单格式为yyyy-MM-dd，月账单格式为yyyy-MM。
	 * @param billType
	 * ALL，返回当日所有订单信息，默认值
	 * SUCCESS，返回当日成功支付的订单
	 * REFUND，返回当日退款订单
	 * RECHARGE_REFUND，返回当日充值退款订单
	 *           
	 * @return 返回支付方下载对账单的结果
	 */
	@Override
	public Map<String, Object> downLoadBill(DqOrderAbstractQuery dqOrderQuery) {

		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();

		parameters.put(DqWxPayKey.BILL__TYPE_KEY, dqOrderQuery.getBillType());
		// 目前只支持日账单
		String billDateStr = DqDateFormatUtils.format(dqOrderQuery.getBillDate(), DqDateFormatUtils.FORMAT_SHORT, TimeZone.getTimeZone("GMT+8"));
		parameters.put(DqWxPayKey.BILL__DATE_KEY, billDateStr);

		// 设置签名
		setSign(parameters);
		String respStr = requestTemplate.postForObject(getUrl(DqWxTransactionType.DOWNLOADBILL),
				DqXMLUtils.getXmlStrFromMap(parameters), String.class);
		if (respStr.indexOf(DqSymbol.LESS_THAN) == 0) {
			Map<String, Object> errorMap = DqXMLUtils.getMapFromXmlStr(respStr);
			if (DqBaseUtils.notEquals(errorMap.get(DqWxPayKey.RETURN__CODE_KEY), DqWxPayValue.SUCCESS)) {
				throw DqBaseBusinessException.newInstance(DqPayErrorCodeEnum.PAY_FAILURE).buildExceptionDetail(errorMap);
			}
		}
		
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put(DqWxPayKey.RETURN__CODE_KEY, DqWxPayValue.SUCCESS);
		retMap.put(DqWxPayKey.RETURN__MSG_KEY, DqWxPayValue.OK);
		retMap.put(DqWxPayKey.DATA_KEY, respStr);
		return retMap;
	}

	/**
	 * @param transactionIdOrBillDate
	 *            支付平台订单号或者账单类型， 具体请 类型为{@link String }或者 {@link Date }
	 *            ，类型须强制限制，类型不对应则抛出异常{@link PayErrorException}
	 * @param outTradeNoBillType
	 *            商户单号或者 账单类型
	 * @param transactionType
	 *            交易类型
	 * @return 返回支付方对应接口的结果
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> secondaryInterface(Object transactionIdOrBillDate, String outTradeNoBillType,
			DqTransactionType transactionType) {

		if (transactionType == DqWxTransactionType.REFUND) {
			throw DqBaseBusinessException.newInstance(DqPayErrorCodeEnum.PAY_GENERAL_INTERFACE_NOT_SUPPORT);
		}

		if (transactionType == DqWxTransactionType.DOWNLOADBILL) {
			if (transactionIdOrBillDate instanceof Date) {
				DqWxOrderQuery dqWxOrderQuery = new DqWxOrderQuery();
				dqWxOrderQuery.setTradeNoOrBillDate(transactionIdOrBillDate);
				dqWxOrderQuery.setOutTradeNoBillType(outTradeNoBillType);
				return downLoadBill(dqWxOrderQuery);
			}
			throw DqBaseBusinessException.newInstance(DqBaseErrorCodeEnum.ILLICIT_TYPE_EXCEPTION);
		}

		if (!(null == transactionIdOrBillDate || transactionIdOrBillDate instanceof String)) {
			throw DqBaseBusinessException.newInstance(DqBaseErrorCodeEnum.ILLICIT_TYPE_EXCEPTION);
		}

		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();
		if (DqStringUtils.isEmpty((String) transactionIdOrBillDate)) {
			parameters.put(DqWxPayKey.OUT__TRADE__NO_KEY, outTradeNoBillType);
		} else {
			parameters.put(DqWxPayKey.TRANSACTION__ID_KEY, transactionIdOrBillDate);
		}
		// 设置签名
		setSign(parameters);
		return requestTemplate.postForObject(getUrl(transactionType), DqXMLUtils.getXmlStrFromMap(parameters),
				HashMap.class);
	}

	/**
	 * 
	 * <p>
	 * 转账
	 * </p>
	 * <p>
	 *  采用标准RSA算法，公钥由微信侧提供,将公钥信息配置在DqPayConfigStorage#setKeyPublic(String)
	 * </p>
	 * <pre>
	 *     所需参数示例及其说明
	 *     参数名称 : 示例值 : 说明 : 是否必须
	 *     dqTransferOrderDTO.payeeAccount : 468555xx : enc_bank_no，收款方银行卡号 : 是
	 *     dqTransferOrderDTO.payeeName : 张三 : 收款方用户名  : 是
	 *     dqTransferOrderDTO.bankStr : ACBC : 收款方开户行枚举字符串 : 是
	 *     dqTransferOrderDTO.amount : 0.01 : 转账金额 : 是
	 *     dqTransferOrderDTO.outNo : WXTON2014578... : partner_trade_no,商户转账订单号 : 是
	 *     dqTransferOrderDTO.remark : 1 : 转账备注, 非必填 : 是
	 * </pre>
	 *
	 * @param dqOrderQuery : DqOrderQuery : 订单查询对象
	 * @return DqBaseServiceResult : 返回对应的转账结果
	 * @author daiqi
	 * 创建时间    2018年2月26日 下午7:04:13
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> transfer(DqTransferOrderDTO order) {
		Map<String, Object> parameters = new TreeMap<String, Object>();
		// 转账到余额
		// parameters.put("mch_appid", payConfigStorage.getAppid());
		parameters.put(DqWxPayKey.MCH__ID_KEY, payConfigStorage.getPid());
		parameters.put(DqWxPayKey.PARTNER__TRADE__NO_KEY, order.getOutNo());
		parameters.put(DqWxPayKey.NONCE__STR_KEY, DqSignUtils.randomStr());
		parameters.put(DqWxPayKey.ENC__BANK__NO_KEY, keyPublic(order.getPayeeAccount()));
		parameters.put(DqWxPayKey.ENC__TRUE__NAME_KEY, keyPublic(order.getPayeeName()));
		parameters.put(DqWxPayKey.BANK__CODE_KEY, order.getBank().getCode());
		parameters.put(DqWxPayKey.AMOUNT_KEY, order.getAmountOfCent()); // 以分为单位
		if (!DqStringUtils.isEmpty(order.getRemark())) {
			parameters.put(DqWxPayKey.DESC_KEY, order.getRemark());
		}
		parameters.put(DqWxPayKey.SIGN_KEY, DqSignUtils.valueOf(payConfigStorage.getSignType()).sign(parameters,
				payConfigStorage.getKeyPrivate(), payConfigStorage.getInputCharset()));
		return getHttpRequestTemplate().postForObject(getUrl(DqWxTransactionType.BANK), parameters, HashMap.class);
	}

	/**
	 * 转账
	 *
	 * @param outNo
	 *            商户转账订单号
	 * @param tradeNo
	 *            支付平台转账订单号
	 *
	 * @return 对应的转账订单
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> queryTransferResult(String outNo, String tradeNo) {
		Map<String, Object> parameters = new TreeMap<String, Object>();
		parameters.put(DqWxPayKey.MCH__ID_KEY, payConfigStorage.getPid());
		parameters.put(DqWxPayKey.PARTNER__TRADE__NO_KEY, DqStringUtils.isEmpty(outNo) ? tradeNo : outNo);
		parameters.put(DqWxPayKey.NONCE__STR_KEY, DqSignUtils.randomStr());
		parameters.put(DqWxPayKey.SIGN_KEY, DqSignUtils.valueOf(payConfigStorage.getSignType()).sign(parameters,
				payConfigStorage.getKeyPrivate(), payConfigStorage.getInputCharset()));
		return getHttpRequestTemplate().postForObject(getUrl(DqWxTransactionType.QUERY_BANK), parameters,
				HashMap.class);
	}

	public String keyPublic(String content) {
		return DqSignUtils.RSA.createSign(content, payConfigStorage.getKeyPublic(), payConfigStorage.getInputCharset());
	}

	/**
	 * 根据交易类型获取url
	 *
	 * @param transactionType
	 *            交易类型
	 *
	 * @return 请求url
	 */
	private String getUrl(DqTransactionType transactionType) {

		return DqWxPayValue.URI + (payConfigStorage.isTest() ? DqWxPayValue.SANDBOXNEW : "") + transactionType.getMethod();
	}

	/**
	 * 获取公共参数
	 *
	 * @return 公共参数
	 */
	private Map<String, Object> getPublicParameters() {
		Map<String, Object> parameters = new TreeMap<String, Object>();
		parameters.put(DqWxPayKey.APPID_KEY, payConfigStorage.getAppid());
		parameters.put(DqWxPayKey.MCH__ID_KEY, payConfigStorage.getPid());
		parameters.put(DqWxPayKey.NONCE__STR_KEY, DqSignUtils.randomStr());
		return parameters;
	}

	/**
	 * 生成并设置签名
	 *
	 * @param parameters
	 *            请求参数
	 * @return 请求参数
	 */
	private Map<String, Object> setSign(Map<String, Object> parameters) {
		parameters.put(DqWxPayKey.SIGN__TYPE_KEY, payConfigStorage.getSignType());
		String sign = createSign(DqSignUtils.parameterText(parameters, DqSymbol.SINGLE_AND, DqWxPayKey.SIGN_KEY, DqWxPayKey.APP_ID_KEY),
				payConfigStorage.getInputCharset());
		parameters.put(DqWxPayKey.SIGN_KEY, sign);
		return parameters;
	}

}
