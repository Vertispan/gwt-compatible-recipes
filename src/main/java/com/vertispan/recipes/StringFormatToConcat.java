package com.vertispan.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.NlsRewrite;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.CoordinateBuilder;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrite all calls to String.format(). Currently only supports %s, %d, etc, and rewrites to plain concatenation
 * of those values into a string, with no locale support or other formatting.
 */
public class StringFormatToConcat extends Recipe {
    private static final MethodMatcher FORMAT_MATCHER = new MethodMatcher("java.lang.String format(java.lang.String,..)");
    private static final MethodMatcher FORMAT_LOCALE_MATCHER = new MethodMatcher("java.lang.String format(java.util.Locale,java.lang.String,..)");

    private static final String ARG_INDEX = "(?:([0-9]+)\\$)?";
    private static final String FLAGS = "(-#\\+ 0,\\()?";
    private static final String WIDTH = "([0-9]+)?";
    private static final String PRECISION = "(?:\\.([0-9]+))?";
    private static final String CONVERSION = "(" +
            "[bB]|" + //boolean
            "[hH]|" +// hex of hashcode
            "[sS]|" +// call formatTo or toString
            "[cC]|" +// character
            "d|" + // decimal integer
            "o|" + // octal integer
            "[xX]|" +// hexadecimal integer
            "[eE]|" +// decimal in scientific notation
            "f|" + // decimal floating point
            "[gG]|" +// decimal floating point, possibly in scientific notation
            "[aA]|" +// floating point in hex with exponent
            "[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc]+|" +// prefix for datetime, all suffixes
            "%|" + // literal percent
            "n)";  // literal newline
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%" +
            ARG_INDEX + // argument index, optional
            FLAGS +     // flags, optional
            WIDTH +     // width, optional
            PRECISION + // precision, optional
            CONVERSION);// conversion type (plus datetime pattern, not yet supported)

    @NlsRewrite.DisplayName
    @Override
    public String getDisplayName() {
        return "Rewrite String.format() to string concatenation";
    }

    @NlsRewrite.Description
    @Override
    public String getDescription() {
        return "Rewrites away String.format() calls, as not compatible with GWT. Locale will be ignored, and all conversions are treated as toString";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {

                if (FORMAT_MATCHER.matches(method)) {
                    List<Expression> args = method.getArguments().subList(1, method.getArguments().size());
                    String formatStr = getConstantStringFromExpression(method.getArguments().get(0))
                            .orElseThrow(() -> new IllegalStateException("String.format()'s format pattern must be a literal string"));
                    return replace(formatStr, args, method.getCoordinates());
                } else if (FORMAT_LOCALE_MATCHER.matches(method)) {
                    // For the locale version, we ignore the locale and just use the format string
                    List<Expression> args = method.getArguments().subList(2, method.getArguments().size());
                    String formatStr = getConstantStringFromExpression(method.getArguments().get(1))
                            .orElseThrow(() -> new IllegalStateException("String.format()'s format pattern must be a literal string"));
                    return replace(formatStr, args, method.getCoordinates());
                }

                return super.visitMethodInvocation(method, executionContext);
            }

            private J replace(String formatStr, List<Expression> args, CoordinateBuilder.MethodInvocation coordinates) {
                formatStr = formatStr.replace("\\", "\\\\") // Escape backslashes
                        .replace("\n", "\\n")// Escape newlines
                        .replace("\r", "\\r") // Escape carriage returns
                        .replace("\"", "\\\""); // Escape double quotes
                Matcher matcher = FORMAT_SPECIFIER.matcher(formatStr);
                StringBuilder sb = new StringBuilder("\"");
                while (matcher.find()) {
                    matcher.appendReplacement(sb, "\" + #{any()} + \"");
                }
                matcher.appendTail(sb);
                sb.append("\"");
                return JavaTemplate.builder(sb.toString()).build().apply(getCursor(), coordinates.replace(), args.toArray());
            }
        };
    }

    /**
     * Type of the expression is assumed to be a string (or we can't convert non-strings to strings freely).
     * @param expr
     * @return
     */
    private static Optional<String> getConstantStringFromExpression(Expression expr) {
        if (expr instanceof J.Literal) {
            return Optional.of((J.Literal) expr).map(J.Literal::getValue).map(Object::toString);
        } else if (expr instanceof J.Binary) {
            J.Binary binary = (J.Binary) expr;
            Optional<String> left = getConstantStringFromExpression(binary.getLeft());
            Optional<String> right = getConstantStringFromExpression(binary.getRight());
            if (left.isPresent() && right.isPresent()) {
                return Optional.of(left.get() + right.get());
            }
        }
        return Optional.empty();
    }
}
