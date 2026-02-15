package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonPOJOBuilder(withPrefix = "")
public abstract class NodeResultBuilderMixin {

    @JsonIgnore
    public abstract NodeResultBuilderMixin error(Throwable error);
}
