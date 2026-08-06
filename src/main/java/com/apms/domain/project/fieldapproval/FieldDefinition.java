package com.apms.domain.project.fieldapproval;

import lombok.Builder;
import lombok.Data;

import java.util.function.BiConsumer;
import java.util.function.Function;

@Data
@Builder
public class FieldDefinition<T> {
    private String canonicalPath;
    private boolean required;
    private boolean collection;
    private boolean orderedCollection;
    private Function<T, Object> getter;
    private BiConsumer<T, Object> setter;
}
