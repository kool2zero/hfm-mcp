package com.hfmmcp.daemon;

import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.mock.MockHfmBackend;
import com.hfmmcp.daemon.http.ApiServer;
import com.hfmmcp.daemon.service.ActionService;
import com.hfmmcp.daemon.service.AuditLog;
import com.hfmmcp.daemon.service.HfmService;
import com.hfmmcp.daemon.session.LoginThrottle;
import com.hfmmcp.daemon.session.SessionManager;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Entry point: {@code java -jar hfm-daemon.jar daemon.properties}. */
public final class DaemonMain {
    private static final Logger LOG = Logger.getLogger(DaemonMain.class.getName());
    private static final String ORACLE_BACKEND = "com.hfmmcp.daemon.backend.oracle.OracleHfmBackend";

    private DaemonMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java -jar hfm-daemon.jar <daemon.properties>");
            System.exit(2);
        }
        DaemonConfig config = DaemonConfig.load(Paths.get(args[0]));
        HfmBackend backend = createBackend(config);

        SessionManager sessions = new SessionManager(backend,
                config.sessionIdleTimeoutMinutes() * 60_000L, config.maxSessions());
        LoginThrottle throttle = new LoginThrottle(config.authMaxFailures(),
                config.authFailureWindowMinutes() * 60_000L);
        HfmService service = new HfmService(backend, sessions, throttle, config);
        ActionService actions = new ActionService(backend, service, config,
                new AuditLog(Paths.get(config.auditLogPath())));
        if (config.actionsEnabled()) {
            LOG.warning("Process-control actions ENABLED: " + config.actionsAllowed()
                    + "; audit log " + config.auditLogPath());
        }
        ApiServer server = new ApiServer(config, service, actions, sessions);

        ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleWithFixedDelay(sessions::evictIdle, 1, 1, TimeUnit.MINUTES);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down; closing HFM sessions");
            server.stop();
            sessions.closeAll();
            stopped.countDown();
        }, "shutdown"));

        server.start();
        stopped.await();
    }

    static HfmBackend createBackend(DaemonConfig config) throws Exception {
        String kind = config.backend();
        if ("mock".equalsIgnoreCase(kind)) {
            LOG.warning("Using the MOCK backend: sample data, any user with password 'password'");
            return new MockHfmBackend();
        }
        if ("oracle".equalsIgnoreCase(kind)) {
            // Loaded reflectively: the Oracle backend is compiled separately against the EPM jars.
            return (HfmBackend) Class.forName(ORACLE_BACKEND).getConstructor(DaemonConfig.class).newInstance(config);
        }
        throw new IllegalArgumentException("backend must be 'mock' or 'oracle', not '" + kind + "'");
    }
}
