package com.dq.easy.cloud.pay.wx.pojo.bo;

import com.dq.easy.cloud.pay.common.payment.pojo.bo.DqRefundOrderBO;
import com.dq.easy.cloud.pay.common.refund.dto.DqRefundOrderAbstractDTO;
import com.dq.easy.cloud.pay.wx.common.utils.DqWxPayUtils.DqWxOrderNoGenerator;

/**
 * 
 * <p>
 * 微信退款订单业务逻辑对象
 * </p>
 *
 * @author daiqi
 * 创建时间    2018年3月1日 下午4:09:31
 */
public class DqWxRefundOrderBO extends DqRefundOrderBO{

	public DqWxRefundOrderBO(DqRefundOrderAbstractDTO dqRefundOrderDTO) {
		super(dqRefundOrderDTO);
	}

	@Override
	public DqRefundOrderBO verifyRefundData() {
		super.verifyCommonData();
		super.verifyTotalAmount();
		return this;
	}

	@Override
	protected DqRefundOrderBO initRefundNo() {
		super.dqRefundOrderDTO.setRefundNo(DqWxOrderNoGenerator.generateWxOrderNO(DqWxTransactionType.REFUND));
		return this;
	}


}
