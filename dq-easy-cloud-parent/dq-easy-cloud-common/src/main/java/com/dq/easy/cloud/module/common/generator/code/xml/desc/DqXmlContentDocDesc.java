package com.dq.easy.cloud.module.common.generator.code.xml.desc;

/**
 * 
 * <p>
 * xml内容文档描述类
 * </p>
 *
 * <pre>
 *  说明：所有xml内容描述文件的基础父类
 * </pre>
 *
 * @author daiqi
 * 创建时间    2018年3月28日 上午10:49:49
 */
public class DqXmlContentDocDesc extends DqXmlContentBaseDesc{
	/** xml的申明 */
	private String statement;
	/** 文档类型 */
	private String docType;
	/** 跟内容元素描述 */
	private DqXmlContentElementDesc rootContentElementDesc;
	
	public String getStatement() {
		return statement;
	}
	public void setStatement(String statement) {
		this.statement = statement;
	}
	public String getDocType() {
		return docType;
	}
	public void setDocType(String docType) {
		this.docType = docType;
	}
	public DqXmlContentElementDesc getRootContentElementDesc() {
		return rootContentElementDesc;
	}
	public void setRootContentElementDesc(DqXmlContentElementDesc rootContentElementDesc) {
		this.rootContentElementDesc = rootContentElementDesc;
	}

}
