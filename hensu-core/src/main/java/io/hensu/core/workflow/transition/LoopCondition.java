package io.hensu.core.workflow.transition;

public abstract class LoopCondition {

    private LoopCondition() {}

    public static final class Always extends LoopCondition {
        public static final Always INSTANCE = new Always();

        private Always() {}
    }

    public static final class Expression extends LoopCondition {
        private final String expr;

        public Expression(String expr) {
            this.expr = expr;
        }

        public String getExpr() {
            return expr;
        }
    }
}
