package io.hensu.core.workflow.transition;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/// Reports which approval arms a set of transition rules declares.
///
/// An approval arm is an {@link ApprovalTransition} — `onApproval` when it expects `true`,
/// `onRejection` when it expects `false`. Decorators are unwrapped through
/// {@link TransitionRule#trigger()}, so a bounded rejection arm still counts as one.
///
/// This is the single source of truth for the question "can a human verdict be routed here?",
/// used by both {@link io.hensu.core.execution.pipeline.ReviewPostProcessor} and
/// {@link io.hensu.core.workflow.validation.WorkflowValidator}. Asking
/// {@link TransitionRule#requiredRoutingVars()} instead would be wrong: a
/// {@link ConditionTransition} routing on some user variable named `approved` would answer yes
/// without offering a verdict anywhere to go, and the two callers would then disagree about
/// which nodes are routable.
///
/// It is also the only place outside the serializers and visualizers that pattern-matches on
/// {@link ApprovalTransition}, keeping that `instanceof` confined to the transition package.
///
/// @see io.hensu.core.workflow.validation.WorkflowValidator for the both-arms requirement
public final class ApprovalArms {

    private ApprovalArms() {}

    /// Returns whether the rules declare the approval arm matching the given verdict.
    ///
    /// @param rules the node's transition rules, not null
    /// @param expected `true` for the `onApproval` arm, `false` for the `onRejection` arm
    /// @return true when at least one rule routes on that verdict
    public static boolean declares(Collection<TransitionRule> rules, boolean expected) {
        return rules.stream().map(TransitionRule::trigger).anyMatch(matching(expected));
    }

    /// Returns whether the rules declare either approval arm.
    ///
    /// @param rules the node's transition rules, not null
    /// @return true when an `onApproval` or an `onRejection` arm is present
    public static boolean declaresAny(Collection<TransitionRule> rules) {
        return declares(rules, true) || declares(rules, false);
    }

    /// Returns whether an unconditional rule ordered after the approval arms can absorb a
    /// verdict that no arm matches.
    ///
    /// A node declaring only `onApproval` normally has nowhere to send a rejection. It is
    /// still well-formed when a catch-all follows the arms — an
    /// {@link AlwaysTransition}, or a {@link SuccessTransition} which matches any successful
    /// result — because the unmatched verdict simply falls through to it. Ordering matters:
    /// a catch-all placed *before* the arms would swallow both verdicts, which is a different
    /// (and worse) problem than the missing arm.
    ///
    /// @param rules the node's transition rules in declaration order, not null
    /// @return true when a catch-all rule follows the last approval arm
    public static boolean absorbsMissingArm(List<TransitionRule> rules) {
        int lastArm = -1;
        for (int i = 0; i < rules.size(); i++) {
            if (isApprovalArm(rules.get(i))) {
                lastArm = i;
            }
        }
        for (int i = lastArm + 1; i < rules.size(); i++) {
            TransitionRule trigger = rules.get(i).trigger();
            if (trigger instanceof AlwaysTransition || trigger instanceof SuccessTransition) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether the rule is unconditional, so that every rule ordered after it is
    /// unreachable.
    ///
    /// Only {@link AlwaysTransition} qualifies: a {@link SuccessTransition} still leaves
    /// failures to later rules. Decorators are unwrapped through
    /// {@link TransitionRule#trigger()}, since a bounded else-arm matches just as
    /// unconditionally as a bare one.
    ///
    /// @param rule the rule to inspect, not null
    /// @return true when the rule matches regardless of state and result
    public static boolean isUnconditional(TransitionRule rule) {
        return rule.trigger() instanceof AlwaysTransition;
    }

    private static boolean isApprovalArm(TransitionRule rule) {
        return rule.trigger() instanceof ApprovalTransition;
    }

    private static Predicate<TransitionRule> matching(boolean expected) {
        return trigger ->
                trigger instanceof ApprovalTransition approval && approval.expected() == expected;
    }
}
