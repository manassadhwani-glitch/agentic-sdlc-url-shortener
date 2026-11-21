package com.example.sdlc.approval;

import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.Risk;
import com.example.sdlc.model.RunState;

import java.time.Clock;
import java.util.List;

/**
 * Applies a human decision to a pending approval, enforcing:
 * <ol>
 *   <li><b>Role-based authority:</b> the approver must hold the required role.</li>
 *   <li><b>Separation of duties (four-eyes):</b> the person who started the run cannot approve its MEDIUM/HIGH-risk
 *       checkpoints.</li>
 *   <li><b>Accountability:</b> rejections must carry a comment, because the comment becomes the agent's rework
 *       feedback.</li>
 * </ol>
 * The decision is only recorded here. The engine picks it up on {@code resume}.
 */
public final class ApprovalService {

    private final ApproverRegistry registry;
    private final Clock clock;

    public ApprovalService(ApproverRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public ApprovalRequest decide(RunState run, String approvalId, String approver, boolean approve, String comment) {
        ApprovalRequest req = run.approval(approvalId)
                .orElseThrow(() -> new ApprovalException("no approval '" + approvalId + "' in run " + run.runId));
        if (req.status != ApprovalRequest.Status.PENDING) {
            throw new ApprovalException("approval " + approvalId + " is already " + req.status);
        }
        if (!registry.isKnown(approver)) {
            throw new ApprovalException("unknown approver '" + approver + "'");
        }
        if (!registry.hasRole(approver, req.requiredRole)) {
            throw new ApprovalException(approver + " does not hold the required role '" + req.requiredRole + "'");
        }
        if (req.risk != Risk.LOW && approver.equals(run.initiator)) {
            throw new ApprovalException("separation of duties: the run initiator (" + run.initiator
                    + ") cannot approve a " + req.risk + "-risk checkpoint");
        }
        if (!approve && (comment == null || comment.isBlank())) {
            throw new ApprovalException("a rejection must include a comment (it becomes the rework feedback)");
        }
        req.status = approve ? ApprovalRequest.Status.APPROVED : ApprovalRequest.Status.REJECTED;
        req.decidedBy = approver;
        req.decidedRole = req.requiredRole;
        req.comment = comment;
        req.decidedAt = clock.instant().toString();
        return req;
    }

    public static List<ApprovalRequest> pending(RunState run) {
        return run.approvals.stream().filter(a -> a.status == ApprovalRequest.Status.PENDING).toList();
    }

    public static class ApprovalException extends RuntimeException {
        public ApprovalException(String message) {
            super(message);
        }
    }
}
