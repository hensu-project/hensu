package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowContextUtilTest {

    @Test
    void shouldFilterInternalKeysAndTolerateNullValues() {
        var context = new HashMap<String, Object>();
        context.put("_tenant_id", "t1");
        context.put("_execution_id", "e1");
        context.put("result", "approved");
        context.put("comment", null);

        Map<String, Object> publicCtx = WorkflowContextUtil.publicContext(context);

        assertThat(publicCtx)
                .containsEntry("result", "approved")
                .containsEntry("comment", null)
                .doesNotContainKey("_tenant_id")
                .doesNotContainKey("_execution_id")
                .hasSize(2);
    }
}
