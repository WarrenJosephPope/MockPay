package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.Membership;
import dev.mockpay.gateway.domain.User;
import dev.mockpay.gateway.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * The logged-in user and the account they are currently acting on.
 *
 * <p>Stored in the HTTP session rather than a thread local, unlike {@link RequestContext}: it must
 * survive between requests, and Spring Session persists it to PostgreSQL so it survives a restart
 * and can be revoked by deleting a row.
 *
 * <p>Only identifiers are held, never the {@link User} object itself. Session attributes are
 * serialised and outlive the request; caching a whole entity there means a stale copy of somebody's
 * role or a password hash sitting in a database column for a fortnight. Ids are re-resolved on each
 * request, which also means <b>a revoked membership takes effect immediately</b> rather than
 * whenever the user next logs in.
 */
public final class DashboardSession {

    private static final String USER_ID = "mockpay.userId";
    private static final String MERCHANT_ID = "mockpay.merchantId";

    /**
     * Only one authority is ever granted, and it means nothing more than "is logged in".
     *
     * <p>Roles are deliberately <em>not</em> put here. Spring Security would cache them in the
     * session, so demoting someone from ADMIN to VIEWER would not take effect until they logged out
     * — which is precisely the stale-authority bug this design avoids by re-reading the membership
     * on every request.
     */
    private static final String AUTHORITY = "DASHBOARD_USER";

    private static final SecurityContextRepository CONTEXT_REPOSITORY =
            new HttpSessionSecurityContextRepository();

    private DashboardSession() {
    }

    /**
     * Log a user in.
     *
     * <p>Two things have to happen together. Our own attributes carry <em>which account</em> the
     * session is acting on, and Spring Security's context is what makes {@code .authenticated()}
     * pass. Setting only the first yields a session that the framework rejects; setting only the
     * second yields a logged-in user acting on no account.
     *
     * <p>Populating the context also fills {@code SPRING_SESSION.PRINCIPAL_NAME}, which is what
     * makes "sign out everywhere" possible later — the sessions are indexed by user.
     */
    public static void establish(HttpServletRequest request, HttpServletResponse response,
                                 String userId, String merchantId) {
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_ID, userId);
        session.setAttribute(MERCHANT_ID, merchantId);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, null, AuthorityUtils.createAuthorityList(AUTHORITY)));
        SecurityContextHolder.setContext(context);
        CONTEXT_REPOSITORY.saveContext(context, request, response);
    }

    public static void switchMerchant(HttpServletRequest request, String merchantId) {
        request.getSession(true).setAttribute(MERCHANT_ID, merchantId);
    }

    public static void destroy(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // Deletes the SPRING_SESSION row. Unlike a JWT, the credential genuinely stops working
            // the moment this returns — there is no window until expiry.
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    public static String userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(USER_ID);
        if (value == null) {
            throw new ApiException(401, "authentication_error", "not_authenticated",
                    "Sign in to continue.");
        }
        return (String) value;
    }

    public static String merchantId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(MERCHANT_ID);
        if (value == null) {
            throw new ApiException(401, "authentication_error", "not_authenticated",
                    "Sign in to continue.");
        }
        return (String) value;
    }

    public static boolean isAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(USER_ID) != null;
    }

    /** Roles are not cached in the session, so this reflects the current grant, not the login-time one. */
    public record Actor(User user, Membership membership) {

        public String merchantId() {
            return membership.getMerchantId();
        }

        public Membership.Role role() {
            return membership.getRole();
        }

        /**
         * Authorise, or refuse with a message that says what is actually required.
         *
         * <p>403 rather than 404: the caller is legitimately authenticated on this account, they
         * simply lack the authority. Hiding that would just make the dashboard confusing. A
         * <em>different</em> account's resources return 404, which is the case where hiding
         * existence genuinely matters.
         */
        public void require(Membership.Role required) {
            if (!membership.getRole().atLeast(required)) {
                throw new ApiException(403, "permission_error", "insufficient_role",
                        "This action requires the " + required + " role or higher. You are "
                                + membership.getRole() + ".");
            }
        }
    }
}
