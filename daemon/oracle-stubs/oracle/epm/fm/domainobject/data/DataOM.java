package oracle.epm.fm.domainobject.data;
import oracle.epm.fm.common.exception.HFMException; import oracle.epm.fm.common.datatype.transport.*; import java.util.*;
public class DataOM { public DataOM(SessionInfo s) throws HFMException {}
 public List<CellDataAndStatusInfo> getCellsDataAndStatus(List<String> p) throws HFMException {return null;}
 public ProcessFlowInfo getProcessFlowInformationByPhase(String pov, int phaseId) throws HFMException {return null;}
 public ServerPMTaskInfo executeServerPMTaskForPovs(PROCESS_FLOW_ACTION a, List<String> povs, PMTaskOptions o) throws HFMException {return null;}
 public ServerTaskInfo executeServerTask(WEBOMDATAGRIDTASKMASKENUM t, List<String> povs) throws HFMException {return null;} }
