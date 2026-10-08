package com.hfmmcp.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.MemberInfo;
import com.hfmmcp.daemon.backend.mock.MockHfmBackend;
import com.hfmmcp.daemon.http.ApiServer;
import com.hfmmcp.daemon.http.Json;
import com.hfmmcp.daemon.service.ActionService;
import com.hfmmcp.daemon.service.AuditLog;
import com.hfmmcp.daemon.service.HfmService;
import com.hfmmcp.daemon.session.LoginThrottle;
import com.hfmmcp.daemon.session.SessionManager;
import com.hfmmcp.daemon.session.UserSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end over HTTP against the mock backend. */
class ApiServerTest {
    private static final String KEY = "test-api-key-0123456789";

    private ApiServer server;
    private SessionManager sessions;
    private String base;
    private Properties props;
    private Path auditFile;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws Exception {
        Properties p = new Properties();
        p.setProperty("server.host", "127.0.0.1");
        p.setProperty("server.port", "0");
        p.setProperty("server.apiKey", KEY);
        p.setProperty("hfm.defaultApplication", "DEMO");
        p.setProperty("limits.maxCellsPerRequest", "50");
        p.setProperty("auth.maxFailures", "2");
        p.setProperty("actions.enabled", "true");
        p.setProperty("actions.waitSeconds", "5");
        p.setProperty("actions.allowed", "START,PROMOTE,SUBMIT,REJECT,CONSOLIDATE,EXTRACT_DATA,COPY_DATA,LOAD_DATA");
        Files.createDirectories(tmp.resolve("loads"));
        p.setProperty("load.allowedDir", tmp.resolve("loads").toString());
        p.setProperty("extract.exportDir", tmp.resolve("exports").toString());
        props = p;
        auditFile = tmp.resolve("audit.log");
        DaemonConfig config = new DaemonConfig(p);
        HfmBackend backend = likeHfm(new MockHfmBackend());
        sessions = new SessionManager(backend, 60_000, 10);
        HfmService service = new HfmService(backend, sessions, new LoginThrottle(2, 60_000), config);
        ActionService actions = new ActionService(backend, service, config, new AuditLog(auditFile));
        server = new ApiServer(config, service, actions, sessions);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    /**
     * Behaves as real HFM does: getMembers ignores the member in {@code {Group.[Children]}} and
     * returns the whole hierarchy, so the daemon must not send it such lists.
     */
    private static HfmBackend likeHfm(MockHfmBackend mock) {
        return (HfmBackend) java.lang.reflect.Proxy.newProxyInstance(HfmBackend.class.getClassLoader(),
                new Class<?>[] {HfmBackend.class}, (proxy, method, args) -> {
                    if ("expandMembers".equals(method.getName()) && ((String) args[2]).contains(".[")) {
                        args[2] = "{[Hierarchy]}";
                    }
                    try {
                        return method.invoke(mock, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void rejectsMissingApiKey() throws IOException {
        Response r = call("GET", "/api/v1/dimensions", null, null, null);
        assertEquals(401, r.status);
        assertEquals("unauthorized", r.body.at("/error/code").asText());
    }

    @Test
    void healthNeedsNoKey() throws IOException {
        Response r = call("GET", "/health", null, null, null);
        assertEquals(200, r.status);
        assertEquals("0.0.0-dev", r.body.get("version").asText()); // release builds stamp the tag version
        JsonNode caps = ok(call("GET", "/api/v1/capabilities", null, KEY, null)); // API key, no login
        assertEquals("0.0.0-dev", caps.get("version").asText());
        assertTrue(caps.get("actionsEnabled").asBoolean());
    }

    @Test
    void loginThenReadDimensionsMembersAndCells() throws IOException {
        String sid = login("jdoe", "password");

        JsonNode dims = ok(call("GET", "/api/v1/dimensions", null, KEY, sid));
        assertEquals("DEMO", dims.get("application").asText());
        assertEquals("Group", dims.at("/defaultPov/Entity").asText());

        JsonNode kids = ok(call("GET", "/api/v1/members?dimension=Entity&member=Group&relation=children", null, KEY, sid));
        assertEquals(2, kids.get("total").asInt());
        assertEquals("US", kids.at("/members/0/name").asText());
        assertEquals("Group", kids.at("/members/0/parent").asText());

        JsonNode base = ok(call("GET", "/api/v1/members?dimension=E&member=group&relation=base", null, KEY, sid));
        assertEquals(3, base.get("total").asInt());
        assertEquals("US01", base.at("/members/0/name").asText());
        assertEquals("UK01", base.at("/members/2/name").asText());

        JsonNode desc = ok(call("GET", "/api/v1/members?dimension=E&member=Group&relation=descendants", null, KEY, sid));
        assertEquals(5, desc.get("total").asInt());

        JsonNode anc = ok(call("GET", "/api/v1/members?dimension=E&member=US01&relation=ancestors", null, KEY, sid));
        assertEquals(2, anc.get("total").asInt());
        assertEquals("US", anc.at("/members/0/name").asText());
        assertEquals("Group", anc.at("/members/1/name").asText());

        JsonNode par = ok(call("GET", "/api/v1/members?dimension=E&expression=%7BUK01.%5BParents%5D%7D", null, KEY, sid));
        assertEquals(1, par.get("total").asInt());
        assertEquals("UK", par.at("/members/0/name").asText());

        JsonNode found = ok(call("GET", "/api/v1/members/search?dimension=A&q=cost", null, KEY, sid));
        assertEquals("COGS", found.at("/members/0/name").asText());

        String body = "{\"pov\":{\"Scenario\":\"Actual\",\"Year\":\"2025\",\"Period\":\"Dec\",\"Account\":\"Sales\"},"
                + "\"vary\":{\"Entity\":[\"{US.[Base]}\",\"UK01\"],\"View\":[\"YTD\",\"Periodic\"]}}";
        JsonNode cells = ok(call("POST", "/api/v1/cells", body, KEY, sid));
        assertEquals(6, cells.get("count").asInt());
        assertEquals("US01", cells.at("/cells/0/members/Entity").asText());
        assertEquals("OK", cells.at("/cells/0/status").asText());
        double ytd = cells.at("/cells/0/value").asDouble();
        double periodic = cells.at("/cells/1/value").asDouble();
        assertEquals(ytd, periodic * 12, 0.01);
    }

    @Test
    void validatePovReportsEachBadMember() throws IOException {
        String sid = login("jdoe", "password");
        JsonNode ok = ok(call("POST", "/api/v1/pov/validate",
                "{\"pov\":\"S#Actual;Budget.Y#2025.P#Dec.E#{Group.[Base]};Group.UK;UK01.A#Sales;{[Base]}.C1#[None]\"}", KEY, sid));
        assertTrue(ok.get("valid").asBoolean(), ok.toString());
        assertEquals(3, ok.at("/dimensions/3/members").asInt());

        JsonNode bad = ok(call("POST", "/api/v1/pov/validate",
                "{\"pov\":\"S#Actul.E#{Nope.[Base]};US.UK01.A#Sales;{NoSuchList}.Q#X\"}", KEY, sid));
        assertFalse(bad.get("valid").asBoolean());
        assertEquals(5, bad.get("invalidCount").asInt());
        assertEquals("Actul", bad.at("/dimensions/0/invalid/0/member").asText());
        assertEquals("Actual", bad.at("/dimensions/0/invalid/0/didYouMean/0").asText());
        assertTrue(bad.at("/dimensions/1/invalid/1/reason").asText().contains("not a parent"));
        assertEquals("Q", bad.at("/unknownDimensions/0").asText());
        assertTrue(bad.get("omittedDimensions").toString().contains("Year"));

        JsonNode typo = ok(call("POST", "/api/v1/pov/validate", "{\"pov\":\"P#Dce.E#Group.UK.A#Sales\"}", KEY, sid));
        assertEquals("Dec", typo.at("/dimensions/0/invalid/0/didYouMean/0").asText(), typo.toString());
        assertEquals(1, typo.get("invalidCount").asInt());
    }

    @Test
    void hfmQualifiedNamesAreSplit() {
        MemberInfo m = MemberInfo.fromHfm("GROUP.BS_BG", "Balance sheet", "", null);
        assertEquals("BS_BG", m.name());
        assertEquals("GROUP", m.parent());
        assertEquals("C", MemberInfo.fromHfm("A.B.C", null, null, null).name());
        assertEquals("B", MemberInfo.fromHfm("A.B.C", null, null, null).parent());
        assertEquals("ROOT", MemberInfo.fromHfm("ROOT", null, "", null).name());
        assertEquals("Group", MemberInfo.fromHfm("US", null, "Group", null).parent());
    }

    @Test
    void parentRollsUpAndBudgetParentNeedsConsolidation() throws IOException {
        String sid = login("jdoe", "password");
        String body = "{\"pov\":{\"Scenario\":\"Budget\",\"Account\":\"Sales\"},\"vary\":{\"Entity\":[\"US\",\"US01\",\"US02\"]}}";
        JsonNode cells = ok(call("POST", "/api/v1/cells", body, KEY, sid));
        double us = cells.at("/cells/0/value").asDouble();
        double sum = cells.at("/cells/1/value").asDouble() + cells.at("/cells/2/value").asDouble();
        assertEquals(sum, us, 0.05);
        assertEquals("CN", cells.at("/cells/0/status").asText());
        assertEquals(1, cells.get("staleCount").asInt());
        assertTrue(cells.at("/statusLegend/CN").asText().startsWith("Needs consolidation"));
    }

    @Test
    void noDataCellHasNullValue() throws IOException {
        String sid = login("jdoe", "password");
        JsonNode cells = ok(call("POST", "/api/v1/cells", "{\"pov\":{\"Year\":\"2026\",\"Period\":\"Dec\"}}", KEY, sid));
        assertTrue(cells.at("/cells/0/value").isNull());
        assertEquals("NODATA", cells.at("/cells/0/flags/0").asText());
    }

    @Test
    void tooManyCellsIsRejected() throws IOException {
        String sid = login("jdoe", "password");
        String body = "{\"vary\":{\"Period\":[\"{[Year].[Descendants]}\"],\"Entity\":[\"{[Hierarchy]}\"]}}";
        Response r = call("POST", "/api/v1/cells", body, KEY, sid);
        assertEquals(400, r.status);
        assertEquals("too_many_cells", r.body.at("/error/code").asText());
    }

    @Test
    void unknownMemberAndDimensionAreBadRequests() throws IOException {
        String sid = login("jdoe", "password");
        Response r = call("GET", "/api/v1/members?dimension=Entity&member=Nope", null, KEY, sid);
        assertEquals(400, r.status);
        assertEquals("unknown_member", r.body.at("/error/code").asText());
        r = call("GET", "/api/v1/members?dimension=Colour", null, KEY, sid);
        assertEquals("unknown_dimension", r.body.at("/error/code").asText());
    }

    @Test
    void invalidMemberFailsOnlyItsOwnCell() throws IOException {
        String sid = login("jdoe", "password");
        JsonNode cells = ok(call("POST", "/api/v1/cells", "{\"vary\":{\"Entity\":[\"US01\",\"Nope\"]}}", KEY, sid));
        assertEquals("OK", cells.at("/cells/0/status").asText());
        assertEquals("ERROR", cells.at("/cells/1/status").asText());
        assertTrue(cells.at("/cells/1/error").asText().contains("Nope"));
        assertTrue(cells.at("/cells/1/value").isNull());
        assertEquals(1, cells.get("errorCount").asInt());
    }

    @Test
    void badPasswordIsThrottled() throws IOException {
        assertEquals(401, call("POST", "/api/v1/sessions", loginBody("amy", "wrong"), KEY, null).status);
        assertEquals(401, call("POST", "/api/v1/sessions", loginBody("amy", "wrong"), KEY, null).status);
        Response r = call("POST", "/api/v1/sessions", loginBody("AMY", "password"), KEY, null);
        assertEquals(429, r.status, "blocked even with the right password until the window passes");
    }

    @Test
    void expiredHfmSessionIsReopenedTransparently() throws Exception {
        String sid = login("jdoe", "password");
        MockHfmBackend.invalidate(sessions.get(sid).backendSession());
        ok(call("GET", "/api/v1/dimensions", null, KEY, sid));
    }

    @Test
    void logoutEndsSession() throws IOException {
        String sid = login("jdoe", "password");
        ok(call("DELETE", "/api/v1/sessions/current", null, KEY, sid));
        Response r = call("GET", "/api/v1/dimensions", null, KEY, sid);
        assertEquals(401, r.status);
        assertEquals("session_expired", r.body.at("/error/code").asText());
    }

    @Test
    void sessionKeepsTokenButNotPassword() throws Exception {
        String sid = login("jdoe", "password");
        UserSession s = sessions.get(sid);
        assertNotNull(s.auth().ssoToken());
        for (Field f : UserSession.class.getDeclaredFields()) {
            assertFalse(f.getName().toLowerCase().contains("password"));
        }
        assertFalse(s.auth().toString().contains(s.auth().ssoToken()));
    }

    // ---------------------------------------------------------------- process control

    @Test
    void processStatusStartsNotStarted() throws IOException {
        String sid = login("jdoe", "password");
        JsonNode st = ok(call("POST", "/api/v1/process/status",
                "{\"pov\":{\"Period\":\"Dec\"},\"entities\":[\"{Group.[Base]}\"],\"phases\":[1,2]}", KEY, sid));
        assertEquals(6, st.get("count").asInt());
        assertEquals(6, st.at("/byState/Not Started").asInt());
        assertEquals("Dec", st.at("/pov/Period").asText());
    }

    @Test
    void previewThenExecuteStart() throws IOException {
        String sid = login("jdoe", "password");
        String preview = "{\"action\":\"start\",\"pov\":{\"Period\":\"Dec\"},\"entities\":[\"US01\",\"UK01\"],"
                + "\"phases\":[1],\"comment\":\"kick off\"}";
        JsonNode plan = ok(call("POST", "/api/v1/actions/preview", preview, KEY, sid));
        assertEquals(2, plan.get("count").asInt());
        assertEquals("Not Started", plan.at("/targets/0/currentState").asText());

        // Nothing happens until execute
        JsonNode st = ok(call("POST", "/api/v1/process/status", "{\"pov\":{\"Period\":\"Dec\"},\"entities\":[\"US01\"]}", KEY, sid));
        assertEquals("Not Started", st.at("/units/0/state").asText());

        String planId = plan.get("planId").asText();
        JsonNode done = ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + planId + "\"}", KEY, sid));
        assertEquals(2, done.get("completed").asInt());

        st = ok(call("POST", "/api/v1/process/status", "{\"pov\":{\"Period\":\"Dec\"},\"entities\":[\"US01\"]}", KEY, sid));
        assertEquals("First Pass", st.at("/units/0/state").asText());
        assertEquals("START", st.at("/units/0/lastAction").asText());
        assertEquals("jdoe", st.at("/units/0/lastUser").asText());
        assertEquals("kick off", st.at("/units/0/lastComment").asText());

        Response again = call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + planId + "\"}", KEY, sid);
        assertEquals(404, again.status, "plans are single-use");

        // A second START is reported as skipped, not failed
        plan = ok(call("POST", "/api/v1/actions/preview", preview, KEY, sid));
        done = ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + plan.get("planId").asText() + "\"}", KEY, sid));
        assertEquals(2, done.get("skipped").asInt());
        assertEquals(0, done.get("failed").asInt());
    }

    @Test
    void startWithDescendantsThenPromote() throws IOException {
        String sid = login("jdoe", "password");
        execute(sid, "{\"action\":\"START\",\"entities\":[\"Group\"],\"includeDescendants\":true}");
        JsonNode st = ok(call("POST", "/api/v1/process/status", "{\"entities\":[\"{[Hierarchy]}\"]}", KEY, sid));
        assertEquals(6, st.at("/byState/First Pass").asInt());

        Response noLevel = call("POST", "/api/v1/actions/preview", "{\"action\":\"PROMOTE\",\"entities\":[\"US01\"]}", KEY, sid);
        assertEquals("bad_level", noLevel.body.at("/error/code").asText());
        execute(sid, "{\"action\":\"PROMOTE\",\"level\":2,\"entities\":[\"US01\"]}");
        st = ok(call("POST", "/api/v1/process/status", "{\"entities\":[\"US01\"]}", KEY, sid));
        assertEquals("Review Level 2", st.at("/units/0/state").asText());
    }

    @Test
    void consolidationClearsNeedsConsolidation() throws IOException {
        String sid = login("jdoe", "password");
        String read = "{\"pov\":{\"Scenario\":\"Budget\",\"Account\":\"Sales\"}}";
        assertEquals("CN", ok(call("POST", "/api/v1/cells", read, KEY, sid)).at("/cells/0/status").asText());

        JsonNode done = execute(sid, "{\"action\":\"CONSOLIDATE\",\"pov\":{\"Scenario\":\"Budget\"},\"entities\":[\"Group\"]}");
        assertTrue(done.get("finished").asBoolean());
        assertEquals("COMPLETED", done.at("/tasks/0/status").asText());

        assertEquals("OK", ok(call("POST", "/api/v1/cells", read, KEY, sid)).at("/cells/0/status").asText());
        JsonNode task = ok(call("POST", "/api/v1/tasks/status", "{\"taskIds\":[" + done.at("/taskIds/0").asInt() + "]}", KEY, sid));
        assertTrue(task.get("finished").asBoolean());
    }

    @Test
    void calculateFinishesWithinTheCall() throws IOException {
        props.setProperty("actions.allowed", "CALCULATE,TRANSLATE");
        String sid = login("jdoe", "password");
        JsonNode done = execute(sid, "{\"action\":\"TRANSLATE\",\"entities\":[\"UK01\"]}");
        assertTrue(done.get("finished").asBoolean());
        assertTrue(done.get("completed").asBoolean());
        assertTrue(Files.readAllLines(auditFile).get(0).contains("\"outcome\":\"completed\""));
    }

    @Test
    void actionsAreAuditLogged() throws IOException {
        String sid = login("jdoe", "password");
        execute(sid, "{\"action\":\"START\",\"entities\":[\"US01\"],\"phases\":[3]}");
        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(1, lines.size());
        JsonNode e = Json.MAPPER.readTree(lines.get(0));
        assertEquals("jdoe", e.get("user").asText());
        assertEquals("START", e.get("action").asText());
        assertEquals(3, e.get("phase").asInt());
        assertEquals("completed", e.get("outcome").asText());
        assertTrue(e.get("target").asText().contains("E#US01"));
    }

    @Test
    void actionsCanBeDisabledOrRestricted() throws IOException {
        String sid = login("jdoe", "password");
        Response r = call("POST", "/api/v1/actions/preview", "{\"action\":\"APPROVE\",\"entities\":[\"US01\"]}", KEY, sid);
        assertEquals(403, r.status);
        assertEquals("action_not_allowed", r.body.at("/error/code").asText());

        props.setProperty("actions.enabled", "false");
        r = call("POST", "/api/v1/actions/preview", "{\"action\":\"START\",\"entities\":[\"US01\"]}", KEY, sid);
        assertEquals("actions_disabled", r.body.at("/error/code").asText());
        assertFalse(ok(call("GET", "/api/v1/capabilities", null, KEY, sid)).get("actionsEnabled").asBoolean());
        // Reading process status still works
        ok(call("POST", "/api/v1/process/status", "{\"entities\":[\"US01\"]}", KEY, sid));
    }

    @Test
    void plansBelongToTheSessionThatMadeThem() throws IOException {
        String a = login("amy", "password");
        String b = login("bob", "password");
        JsonNode plan = ok(call("POST", "/api/v1/actions/preview", "{\"action\":\"START\",\"entities\":[\"US01\"]}", KEY, a));
        Response r = call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + plan.get("planId").asText() + "\"}", KEY, b);
        assertEquals(404, r.status);
    }

    // ---------------------------------------------------------------- extract and copy

    @Test
    void flatFileExtractIsFetchedAndPreviewed() throws IOException {
        String sid = login("jdoe", "password");
        JsonNode plan = ok(call("POST", "/api/v1/actions/preview-extract",
                "{\"slice\":\"S#Actual.Y#2025.P{[Base]}.E{Group.[Base]}\",\"prefix\":\"DEC25\"}", KEY, sid));
        assertEquals("FLATFILE", plan.get("format").asText());
        // HFM needs every dimension: the omitted ones come from the default POV, E{...} becomes E#{...}.
        assertEquals("S#Actual.Y#2025.P#{[Base]}.W#YTD.V#<Entity Currency>.E#{Group.[Base]}.A#NetIncome"
                + ".I#[ICP None].C1#[None].C2#[None].C3#[None].C4#[None]", plan.get("slice").asText());
        assertTrue(plan.get("filledFromDefaultPov").toString().contains("View = YTD"), plan.toString());
        JsonNode done = ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + plan.get("planId").asText() + "\"}", KEY, sid));
        assertTrue(done.get("finished").asBoolean());
        JsonNode file = done.at("/files/0");
        assertTrue(file.get("path").asText().replace('\\', '/').contains("exports/DEC25_"));
        assertEquals(12, file.get("lines").asInt());
        assertTrue(file.at("/preview/0").asText().startsWith("Actual;2025;Dec;"));
        assertTrue(Files.exists(java.nio.file.Paths.get(file.get("path").asText())));
    }

    @Test
    void extractValidation() throws IOException {
        String sid = login("jdoe", "password");
        Response r = call("POST", "/api/v1/actions/preview-extract",
                "{\"slice\":\"S#Actual\",\"prefix\":\"../evil\"}", KEY, sid);
        assertEquals(400, r.status);
        r = call("POST", "/api/v1/actions/preview-extract",
                "{\"slice\":\"S#Actual\",\"prefix\":\"EA\",\"format\":\"WAREHOUSE\"}", KEY, sid);
        assertTrue(r.body.at("/error/message").asText().contains("dsn"));
        JsonNode ok = ok(call("POST", "/api/v1/actions/preview-extract",
                "{\"slice\":\"S#Actual\",\"prefix\":\"EA\",\"format\":\"warehouse\",\"dsn\":\"Oracle\"}", KEY, sid));
        assertTrue(ok.get("effect").asText().contains("replaced"));
    }

    @Test
    void copyDataCopiesValuesAndIsAudited() throws IOException {
        String sid = login("jdoe", "password");
        String actual = "{\"pov\":{\"Scenario\":\"Actual\",\"Period\":\"Nov\",\"Entity\":\"US01\",\"Account\":\"Sales\"}}";
        String budget = actual.replace("Actual", "Budget");
        double a = ok(call("POST", "/api/v1/cells", actual, KEY, sid)).at("/cells/0/value").asDouble();
        assertTrue(a != ok(call("POST", "/api/v1/cells", budget, KEY, sid)).at("/cells/0/value").asDouble());

        // A member in braces, or a misspelt one, is refused at preview rather than by HFM at execution.
        Response bad = call("POST", "/api/v1/actions/preview-copy", "{\"source\":\"S#Actual.Y#2025.P#Nov\","
                + "\"target\":\"S#Budget.Y#2025.P#Nov\",\"entitiesAndAccounts\":\"E{US.US01}.A{[Base]}\"}", KEY, sid);
        assertEquals(400, bad.status);
        assertEquals("bad_pov", bad.body.at("/error/code").asText());
        assertTrue(bad.body.at("/error/message").asText().contains("E#US01"), bad.body.toString());
        bad = call("POST", "/api/v1/actions/preview-extract",
                "{\"slice\":\"S#Actul.Y#2025.P#Nov.E{US.[Base]}\",\"prefix\":\"EA\",\"format\":\"FLATFILE\"}", KEY, sid);
        assertEquals(400, bad.status);
        assertTrue(bad.body.at("/error/message").asText().contains("did you mean [Actual]"), bad.body.toString());

        JsonNode plan = ok(call("POST", "/api/v1/actions/preview-copy", "{\"source\":\"S#Actual.Y#2025.P#Nov\","
                + "\"target\":\"S#Budget.Y#2025.P#Nov\",\"entitiesAndAccounts\":\"E{US.[Base]}.A{[Base]}\"}", KEY, sid));
        assertEquals("MERGE", plan.get("mode").asText());
        assertTrue(plan.get("effect").asText().contains("S#Budget.Y#2025.P#Nov"));
        // Previewing changes nothing
        assertTrue(a != ok(call("POST", "/api/v1/cells", budget, KEY, sid)).at("/cells/0/value").asDouble());

        JsonNode done = ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + plan.get("planId").asText() + "\"}", KEY, sid));
        assertTrue(done.get("completed").asBoolean());
        assertEquals(a, ok(call("POST", "/api/v1/cells", budget, KEY, sid)).at("/cells/0/value").asDouble(), 0.001);

        String last = Files.readAllLines(auditFile).get(0);
        assertTrue(last.contains("COPY_DATA") && last.contains("S#Actual.Y#2025.P#Nov -> S#Budget.Y#2025.P#Nov"));
    }

    @Test
    void scanThenLoadDataFile() throws IOException {
        String sid = login("jdoe", "password");
        Files.write(tmp.resolve("loads").resolve("dec.dat"), java.util.Arrays.asList(
                "!Data",
                "Actual;2025;Sep;YTD;US01;<Entity Currency>;Sales;[ICP None];[None];[None];[None];[None];1234.5",
                "Actual;2025;Sep;YTD;Nowhere;<Entity Currency>;Sales;[ICP None];[None];[None];[None];[None];1"));
        String read = "{\"pov\":{\"Period\":\"Sep\",\"Entity\":\"US01\",\"Account\":\"Sales\"}}";
        double before = ok(call("POST", "/api/v1/cells", read, KEY, sid)).at("/cells/0/value").asDouble();

        JsonNode scan = ok(call("POST", "/api/v1/actions/preview-load",
                "{\"file\":\"dec.dat\",\"delimiter\":\";\",\"scanOnly\":true}", KEY, sid));
        assertEquals(3, scan.at("/file/lines").asInt());
        assertTrue(scan.get("effect").asText().startsWith("SCAN only"));
        JsonNode scanned = ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + scan.get("planId").asText() + "\"}", KEY, sid));
        String log = scanned.at("/logs/0/text").asText();
        assertTrue(log.contains("invalid member 'Nowhere'"), log);
        assertEquals(before, ok(call("POST", "/api/v1/cells", read, KEY, sid)).at("/cells/0/value").asDouble(), 0.001);

        JsonNode plan = ok(call("POST", "/api/v1/actions/preview-load", "{\"file\":\"dec.dat\",\"delimiter\":\";\"}", KEY, sid));
        assertTrue(plan.get("effect").asText().contains("changes data"));
        ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + plan.get("planId").asText() + "\"}", KEY, sid));
        assertEquals(1234.5, ok(call("POST", "/api/v1/cells", read, KEY, sid)).at("/cells/0/value").asDouble(), 0.001);
        assertTrue(Files.readAllLines(auditFile).toString().contains("LOAD_DATA SCAN"));
    }

    @Test
    void loadFilesMustBeInTheLoadFolder() throws IOException {
        String sid = login("jdoe", "password");
        Files.write(tmp.resolve("secret.dat"), java.util.Collections.singletonList("x"));
        Response r = call("POST", "/api/v1/actions/preview-load", "{\"file\":\"../secret.dat\"}", KEY, sid);
        assertTrue(r.status == 403 || r.status == 400, r.body.toString());
        r = call("POST", "/api/v1/actions/preview-load",
                "{\"file\":\"" + tmp.resolve("secret.dat").toString().replace("\\", "\\\\") + "\"}", KEY, sid);
        assertTrue(r.status == 403 || r.status == 400, r.body.toString());

        props.remove("load.allowedDir");
        r = call("POST", "/api/v1/actions/preview-load", "{\"file\":\"dec.dat\"}", KEY, sid);
        assertTrue(r.body.at("/error/message").asText().contains("load.allowedDir"));
    }

    @Test
    void extractAndCopyAreOffByDefault() throws IOException {
        props.remove("actions.allowed");
        String sid = login("jdoe", "password");
        Response r = call("POST", "/api/v1/actions/preview-copy", "{\"source\":\"S#Actual.Y#2025.P#Nov\","
                + "\"target\":\"S#Budget.Y#2025.P#Nov\",\"entitiesAndAccounts\":\"E{US.[Base]}\"}", KEY, sid);
        assertEquals("action_not_allowed", r.body.at("/error/code").asText());
        r = call("POST", "/api/v1/actions/preview-extract", "{\"slice\":\"S#Actual\",\"prefix\":\"EA\"}", KEY, sid);
        assertEquals("action_not_allowed", r.body.at("/error/code").asText());
    }

    private JsonNode execute(String sid, String previewBody) throws IOException {
        JsonNode plan = ok(call("POST", "/api/v1/actions/preview", previewBody, KEY, sid));
        return ok(call("POST", "/api/v1/actions/execute", "{\"planId\":\"" + plan.get("planId").asText() + "\"}", KEY, sid));
    }

    // ---------------------------------------------------------------- helpers

    private String login(String user, String password) throws IOException {
        JsonNode r = ok(call("POST", "/api/v1/sessions", loginBody(user, password), KEY, null));
        String sid = r.get("sessionId").asText();
        assertTrue(sid.length() >= 40);
        assertNull(r.get("ssoToken"), "the CSS token never leaves the daemon");
        return sid;
    }

    private static String loginBody(String user, String password) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}";
    }

    private static JsonNode ok(Response r) {
        assertEquals(200, r.status, () -> "unexpected response: " + r.body);
        return r.body;
    }

    private static final class Response {
        final int status;
        final JsonNode body;

        Response(int status, JsonNode body) {
            this.status = status;
            this.body = body;
        }
    }

    private Response call(String method, String path, String body, String key, String sid) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        if (key != null) {
            c.setRequestProperty("X-Api-Key", key);
        }
        if (sid != null) {
            c.setRequestProperty("X-Session-Id", sid);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return new Response(status, Json.MAPPER.readTree(buf.toByteArray()));
    }

}
