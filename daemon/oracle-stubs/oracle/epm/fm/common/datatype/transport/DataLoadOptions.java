package oracle.epm.fm.common.datatype.transport;
public class DataLoadOptions implements org.apache.thrift.TBase<DataLoadOptions, Object> {
 public DataLoadOptions setAccumulateWithinFile(boolean b){return this;} public DataLoadOptions setAppendToLogFile(boolean b){return this;}
 public DataLoadOptions setContainSharesData(boolean b){return this;} public DataLoadOptions setContainSubmissionPhaseData(boolean b){return this;}
 public DataLoadOptions setDecimalChar(String s){return this;} public DataLoadOptions setThousandsChar(String s){return this;}
 public DataLoadOptions setDelimiter(String s){return this;} public DataLoadOptions setDuplicates(DATALOAD_DUPLICATE_HANDLING d){return this;}
 public DataLoadOptions setFileFormat(DATALOAD_FILE_FORMAT f){return this;} public DataLoadOptions setLoadCalculated(boolean b){return this;}
 public DataLoadOptions setMode(LOAD_MODE m){return this;} public DataLoadOptions setUserFileName(String s){return this;} }
