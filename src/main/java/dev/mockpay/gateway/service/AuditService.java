package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.AuditLog;
import dev.mockpay.gateway.repo.AuditLogRepository;
import dev.mockpay.gateway.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the audit trail.
 *
 * <p>Every mutating dashboard action calls this. The rule worth holding to: if an action changes
 * state that a merchant could later dispute — a key issued, a refund sent, a teammate added — it is
 * audited, and the record includes enough to answer "was that really them?".
 *
 * <p>Deliberately never throws. An audit write failing must not roll back the payment it describes;
 * losing one log line is bad, losing a customer's refund because logging failed is worse. The
 * failure is logged loudly instead, which is what an alert should be watching.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository entries;

    public AuditService(AuditLogRepository entries) {
        this.entries = entries;
    }

    @Transactional
    public void record(String merchantId, String userId, String userEmail, String action,
                       String targetType, String targetId, Map<String, Object> detail) {
        try {
            entries.save(new AuditLog(
                    Ids.generate("aud"), merchantId, userId, userEmail, action,
                    targetType, targetId, renderDetail(detail),
                    currentIp(), truncate(currentUserAgent(), 490)));
        } catch (Exception e) {
            log.error("AUDIT WRITE FAILED for {} on {} {} by {} — investigate",
                    action, targetType, targetId, userEmail, e);
        }
    }

    public Page<AuditLog> list(String merchantId, Pageable pageable) {
        return entries.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }

    public Map<String, Object> snapshot(AuditLog entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("object", "audit_log_entry");
        map.put("action", entry.getAction());
        map.put("actor", entry.getUserEmail());
        map.put("target_type", entry.getTargetType());
        map.put("target_id", entry.getTargetId());
        map.put("detail", entry.getDetail());
        map.put("ip_address", entry.getIpAddress());
        map.put("created", entry.getCreatedAt().getEpochSecond());
        return map;
    }

    /**
     * Flattened to a small JSON-ish string rather than a nested structure.
     *
     * <p>Audit detail is read by humans during an investigation and grepped by tooling. Both want
     * one line. It is also deliberately not the full request body — that would put card data and
     * secrets into a table that outlives everything else.
     */
    private String renderDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        detail.forEach((k, v) -> sb.append(sb.length() > 1 ? ", " : "")
                .append(k).append("=").append(v));
        return truncate(sb.append("}").toString(), 1990);
    }

    private String currentIp() {
        var request = currentRequest();
        if (request == null) {
            return null;
        }
        // X-Forwarded-For, when present, is the client address as seen before any proxy. Only
        // trustworthy behind a proxy you control — an attacker can otherwise set it to anything.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String currentUserAgent() {
        var request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    private jakarta.servlet.http.HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
