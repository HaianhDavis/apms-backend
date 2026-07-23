package com.apms.domain.score.service;

import com.apms.domain.score.draft.RoleEvaluationDraft;

public interface RoleEvaluationContextProvider<T> {
    T buildContext(RoleEvaluationDraft draft, String criterionKey);
}
