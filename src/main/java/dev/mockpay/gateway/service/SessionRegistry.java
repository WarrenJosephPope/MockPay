package dev.mockpay.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Ends every session belonging to a user.
 *
 * <p>This is what makes "sign out everywhere" possible, and it is the single strongest argument for
 * server-side sessions over a stateless token. A JWT cannot be recalled: once issued it is valid
 * until it expires, so a password reset leaves any stolen token working until then. Here the
 * sessions are rows, and deleting them takes effect on the intruder's very next request.
 *
 * <p>Spring Session indexes rows by principal name, which is populated because
 * {@code DashboardSession} puts the user id into Spring Security's context at login. Without that,
 * the lookup below would find nothing and this class would silently do no work.
 */
@Service
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /**
     * Deliberately the raw type, obtained through a provider.
     *
     * <p>Two Spring wrinkles meet here. The implementation declares
     * {@code FindByIndexNameSessionRepository<JdbcSession>}, and injecting a wildcard
     * ({@code <? extends Session>}) does not match it — Spring resolves the wildcard to
     * {@code <Session>} and finds no candidate, failing startup. The raw type sidesteps that.
     *
     * <p>And {@code ObjectProvider} keeps it optional, so switching the session store off does not
     * prevent the application from booting; "sign out everywhere" simply degrades to a warning.
     */
    @SuppressWarnings("rawtypes")
    private final ObjectProvider<FindByIndexNameSessionRepository> sessions;

    @SuppressWarnings("rawtypes")
    public SessionRegistry(ObjectProvider<FindByIndexNameSessionRepository> sessions) {
        this.sessions = sessions;
    }

    /**
     * @return how many sessions were ended
     */
    @SuppressWarnings("unchecked")
    public int invalidateAllFor(String userId) {
        FindByIndexNameSessionRepository<? extends Session> repository =
                sessions.getIfAvailable();

        if (repository == null) {
            log.warn("No indexed session repository — cannot sign {} out of other sessions. "
                    + "Any session they already hold remains valid.", userId);
            return 0;
        }

        Map<String, ? extends Session> found = repository.findByPrincipalName(userId);
        found.keySet().forEach(repository::deleteById);

        if (!found.isEmpty()) {
            log.info("Invalidated {} session(s) for {}", found.size(), userId);
        }
        return found.size();
    }
}
