package oracle.epm.fm.domainobject.application;
import oracle.epm.fm.common.exception.HFMException; import oracle.epm.fm.common.datatype.transport.SessionInfo; import java.util.Locale;
public class SessionOM { public SessionOM() throws HFMException {}
 public String getAvailableCluster(String t) throws HFMException {return null;}
 public SessionInfo createSession(String t, Locale l, String c, String a) throws HFMException {return null;}
 public java.util.Map<String,String> getDSNDetails(String c, String t, String d) throws HFMException {return null;}
 public void closeSession(SessionInfo s) throws HFMException {} }
