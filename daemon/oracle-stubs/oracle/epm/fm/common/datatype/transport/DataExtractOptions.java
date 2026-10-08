package oracle.epm.fm.common.datatype.transport;
public class DataExtractOptions implements org.apache.thrift.TBase<DataExtractOptions, Object> {
 public DataExtractOptions setIncludeData(boolean b){return this;} public DataExtractOptions setIncludeCalculatedData(boolean b){return this;}
 public DataExtractOptions setIncludeDerivedData(boolean b){return this;} public DataExtractOptions setIncludeDynamicAccounts(boolean b){return this;}
 public DataExtractOptions setLineItemOption(DATA_LINEITEM_OPTION o){return this;} public DataExtractOptions setTablePrefix(String s){return this;}
 public DataExtractOptions setMetadataSlice(String s){return this;} public DataExtractOptions setDelimiter(String s){return this;}
 public DataExtractOptions setExtractFormat(DATA_EXTRACT_TYPE_FLAG f){return this;} public DataExtractOptions setMapDbConnectInfo(java.util.Map<String,String> m){return this;}
 public DataExtractOptions setDSN(String s){return this;} public DataExtractOptions setDatabaseOption(DATA_PUSH_OPTION o){return this;} }
