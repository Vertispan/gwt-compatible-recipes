package com.vertispan.recipes;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.NlsRewrite;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.JavadocVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Javadoc;
import org.openrewrite.marker.Markers;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records all dependencies from any type to another, then traverses the graph starting from
 * the entrypoint types to find all reachable types. All other types are removed.
 */
public class EliminateUnreachableTypes extends ScanningRecipe<Map<String, EliminateUnreachableTypes.TypeModel>> {
    private final Set<String> entrypointTypes;

    // true to respect links in javadoc, false to ignore them and rewrite where necessary
    private final boolean checkDocumentation;

    public EliminateUnreachableTypes(@JsonProperty("entrypointTypes") List<String> entrypointTypes, @JsonProperty("checkDocumentation") Boolean checkDocumentation) {
        this.entrypointTypes = Set.copyOf(entrypointTypes);
        this.checkDocumentation = checkDocumentation != null && checkDocumentation;
    }

    @NlsRewrite.DisplayName
    @Override
    public String getDisplayName() {
        return "Eliminate unreachable types";
    }

    @NlsRewrite.Description
    @Override
    public String getDescription() {
        return "Given a set of entrypoint types, eliminate all types that are not reachable from those entrypoints.";
    }

    @Override
    public Map<String, TypeModel> getInitialValue(ExecutionContext ctx) {
        return new LinkedHashMap<>();
    }


    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Map<String, TypeModel> acc) {
        return new ScanAllDependencies(acc);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Map<String, TypeModel> acc) {
        // Given the discovered map and the provided set of entrypoints, first work out the
        // reachable types, then visit to keep those types.
        Set<String> keep = new LinkedHashSet<>();
        for (String entrypointType : entrypointTypes) {
            TypeModel typeModel = acc.get(entrypointType);
            if (typeModel == null) {
                throw new IllegalStateException("Didn't find type " + entrypointType + " in the sources");
            }
            recordDependencies(acc, keep, Set.of(typeModel.type));
            if (!keep.contains(typeModel.type.getFullyQualifiedName())) {
                throw new IllegalStateException("Didn't actually keep " + entrypointType);
            }
        }
        Set<String> remove = new LinkedHashSet<>(acc.keySet());
        remove.removeAll(keep);
        return new EliminateUnreachableTypesVisitor(keep, remove);
    }

    private void recordDependencies(Map<String, TypeModel> acc, Set<String> keep, Set<JavaType.Class> types) {
        recordDependencies(acc, keep, types, 0);
    }
    private void recordDependencies(Map<String, TypeModel> acc, Set<String> keep, Set<JavaType.Class> types, int depth) {
        for (JavaType.Class type : types) {
            TypeModel typeModel = acc.get(type.getFullyQualifiedName());
            if (typeModel != null && keep.add(type.getFullyQualifiedName())) {
//                System.out.println("  ".repeat(depth) + type);
                // If we have sources for this type and haven't already added it, add it and all its dependencies
                recordDependencies(acc, keep, typeModel.getDependencies(), depth + 1);
            }
        }
    }

    public static class TypeModel {
        // The compilation unit that the type appears in - if null, it is from a dependency, and we can't prune it.
        private J.CompilationUnit compilationUnit;
        // The type itself that this represents
        private final JavaType.Class type;
        // Types that this type depends on
        private final Set<JavaType.Class> dependencies = new HashSet<>();

        public TypeModel(JavaType.Class type) {
            this.type = type;
        }

        public void addDependency(JavaType.Class dependency) {
            dependencies.add(dependency);
        }
        public Set<JavaType.Class> getDependencies() {
            return dependencies;
        }
    }

    public class ScanAllDependencies extends JavaIsoVisitor<ExecutionContext> {
        private final Map<String, TypeModel> typeModels;
        private TypeModel currentTypeModel;
        private final Set<TypeModel> inCompilationUnit = new HashSet<>();

        public ScanAllDependencies(Map<String, TypeModel> typeModels) {
            this.typeModels = typeModels;
        }

        @Override
        protected JavadocVisitor<ExecutionContext> getJavadocVisitor() {
            if (checkDocumentation) {
                return super.getJavadocVisitor();
            }

            return new JavadocVisitor<>(new JavaVisitor<>()) {
                @Override
                public Javadoc visitReference(Javadoc.Reference reference, ExecutionContext executionContext) {
                    return super.visitReference(reference, executionContext);
                }
            };
        }

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext executionContext) {
            assert inCompilationUnit.isEmpty();
            J.CompilationUnit compilationUnit = super.visitCompilationUnit(cu, executionContext);
            for (TypeModel typeModel : inCompilationUnit) {
                typeModel.compilationUnit = compilationUnit;
            }
            inCompilationUnit.clear();
            return compilationUnit;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext executionContext) {
            TypeModel prev = currentTypeModel;
            currentTypeModel = new TypeModel(raw(classDecl.getType()).get());
            inCompilationUnit.add(currentTypeModel);
            typeModels.put(classDecl.getType().getFullyQualifiedName(), currentTypeModel);
            J.ClassDeclaration classDeclaration = super.visitClassDeclaration(classDecl, executionContext);
            currentTypeModel = prev;
            return classDeclaration;
        }
        @Override
        public J.Import visitImport(J.Import _import, ExecutionContext p) {
            // Don't descend into the tree and mark this type
            return _import;
        }

        @Override
        public J.Package visitPackage(J.Package pkg, ExecutionContext executionContext) {
            // Don't visit the package decl
            return pkg;
        }

        @Override
        public @Nullable JavaType visitType(@Nullable JavaType javaType, ExecutionContext p) {
            raw(javaType).ifPresent(currentTypeModel::addDependency);

            return super.visitType(javaType, p);
        }
    }

    public class EliminateUnreachableTypesVisitor extends JavaIsoVisitor<ExecutionContext> {
        private class EliminatedPrunedJavadocRefsVisitor extends JavaVisitor<ExecutionContext> {
            private boolean pruneCurrentReference = false;
            private class JavadocRefVisitor extends JavadocVisitor<ExecutionContext> {

                public JavadocRefVisitor() {
                    super(EliminatedPrunedJavadocRefsVisitor.this);
                }

                @Override
                public Javadoc visitLink(Javadoc.Link link, ExecutionContext executionContext) {
                    pruneCurrentReference = false;
                    Javadoc.Link result = (Javadoc.Link) super.visitLink(link, executionContext);
                    if (pruneCurrentReference) {
                        return new Javadoc.Text(UUID.randomUUID(), Markers.EMPTY, result.getTreeReference().getTree().toString());
                    }
                    return result;
                }

                @Override
                public Javadoc visitSee(Javadoc.See see, ExecutionContext executionContext) {
                    pruneCurrentReference = false;
                    Javadoc result = super.visitSee(see, executionContext);

                    if (pruneCurrentReference) {
                        return new Javadoc.Text(UUID.randomUUID(), Markers.EMPTY, see.getTreeReference().getTree().toString());
                    }
                    return result;
                }
            }

            @Override
            @Nullable
            public JavaType visitType(@Nullable JavaType javaType, ExecutionContext executionContext) {
                if (javaType == null) {
                    return null;
                }
                JavaType type = super.visitType(javaType, executionContext);
                if (type instanceof JavaType.Primitive || type instanceof JavaType.Unknown || type instanceof JavaType.GenericTypeVariable) {
                    // no child nodes that we care about
                    return type;
                } else if (type instanceof JavaType.Method) {
                    // visit types in the method sig, but don't worry about return value, just make sure we prune
                    // if anything is unreachable after this recipe
                    visitType(((JavaType.Method) type).getReturnType(), executionContext);
                    for (JavaType param : ((JavaType.Method) type).getParameterTypes()) {
                        visitType(param, executionContext);
                    }
                    return type;
                } else if (type instanceof JavaType.Variable) {
                    visitType(((JavaType.Variable) type).getType(), executionContext);
                    return type;
                } else if (type instanceof JavaType.Array) {
                    visitType(((JavaType.Array) type).getElemType(), executionContext);
                    return type;
                }
                Optional<JavaType.Class> raw = raw(type);
                if (remove.contains(raw.get().getFullyQualifiedName())) {
                    pruneCurrentReference = true;
                }
                return type;
            }
        }

        private final Set<String> keep;
        // Whereas "keep" is the set of types that should exist in this project after this pass completes, the "remove"
        // list might contain types we can't actually remove. Used only for javadoc reference rewrites.
        private final Set<String> remove;

        public EliminateUnreachableTypesVisitor(Set<String> keep, Set<String> remove) {
            this.keep = keep;
            this.remove = remove;
        }

        @Override
        protected JavadocVisitor<ExecutionContext> getJavadocVisitor() {
            if (checkDocumentation) {
                return super.getJavadocVisitor();
            }
            return new EliminatedPrunedJavadocRefsVisitor().new JavadocRefVisitor();
        }

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext executionContext) {
            J.CompilationUnit compilationUnit = super.visitCompilationUnit(cu, executionContext);
            if (compilationUnit.getClasses().isEmpty()) {
                // No types in this file, remove it.
                // Despite the warning about returning null, this seems to work?
                return null;
            }
            return compilationUnit;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext executionContext) {
            Optional<JavaType.Class> raw = raw(classDecl.getType());
            if (raw.isEmpty() || !keep.contains(raw.get().getFullyQualifiedName())) {
                // If the type is not in the keep set, remove it.
                // Despite the warning about returning null, this seems to work?
                return null;
            }
            return super.visitClassDeclaration(classDecl, executionContext);
        }
    }

    private static Optional<JavaType.Class> raw(JavaType type) {
        if (type instanceof JavaType.Class) {
            return Optional.of((JavaType.Class) type);
        } else if (type instanceof JavaType.Parameterized) {
            return raw(((JavaType.Parameterized) type).getType());
        } else if (type instanceof JavaType.GenericTypeVariable) {
            List<JavaType> bounds = ((JavaType.GenericTypeVariable) type).getBounds();
            return bounds.isEmpty() ? Optional.empty() : raw(bounds.get(0));
        }
        return Optional.empty();
    }
}
