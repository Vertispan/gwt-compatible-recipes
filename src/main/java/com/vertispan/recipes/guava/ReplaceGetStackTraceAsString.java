package com.vertispan.recipes.guava;

import org.openrewrite.ExecutionContext;
import org.openrewrite.NlsRewrite;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;

public class ReplaceGetStackTraceAsString extends Recipe {
    private static final MethodMatcher TARGET_METHOD = new MethodMatcher(
            "com.google.common.base.Throwables getStackTraceAsString(..)");
    private static final JavaTemplate REPLACEMENT = JavaTemplate.builder("#{any(java.lang.Throwable)}.getMessage()")
            .build();
    @NlsRewrite.DisplayName
    @Override
    public String getDisplayName() {
        return "Rewrite Guava getStackTraceAsString() to Throwable.getMessage()";
    }

    @NlsRewrite.Description
    @Override
    public String getDescription() {
        return "Guava's Throwables.getStackTraceAsString() is marked as GwtIncompatible - replacing it with something that at least returns a string helps get errors to users";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {
                if (TARGET_METHOD.matches(method)) {
                    return REPLACEMENT.apply(getCursor(), method.getCoordinates().replace(), method.getArguments().get(0));
                }
                return super.visitMethodInvocation(method, executionContext);
            }
        };
    }
}
