package org.jctools.queues.atomic;

import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithType;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

public final class JavaParsingAtomicArrayQueueGenerator extends JavaParsingAtomicQueueGenerator {
    private static final String GEN_DIRECTIVE_CLASS_CONTAINS_ORDERED_FIELD_ACCESSORS = "$gen:ordered-fields";
    private static final String GEN_DIRECTIVE_METHOD_IGNORE = "$gen:ignore";

    public static void main(String[] args) throws Exception {
        main(JavaParsingAtomicArrayQueueGenerator.class, args);
    }

    JavaParsingAtomicArrayQueueGenerator(String sourceFileName) {
        super(sourceFileName);
    }

    @Override
    public void visit(ConstructorDeclaration n, Void arg) {
        super.visit(n, arg);
        // Update the ctor to match the class name
        n.setName(translateQueueName(n.getNameAsString()));
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration node, Void arg) {
        super.visit(node, arg);

        replaceParentClassesForAtomics(node);

        node.setName(translateQueueName(node.getNameAsString()));

        if (isCommentPresent(node, GEN_DIRECTIVE_CLASS_CONTAINS_ORDERED_FIELD_ACCESSORS)) {
            node.setComment(null);
            removeStaticFieldsAndInitialisers(node);
            patchAtomicFieldUpdaterAccessorMethods(node);
        }

        for (MethodDeclaration method : node.getMethods()) {
            if (isCommentPresent(method, GEN_DIRECTIVE_METHOD_IGNORE)) {
                method.remove();
            }
        }

        if (!node.getMethodsByName("failFastOffer").isEmpty()) {
            MethodDeclaration deprecatedMethodRedirect = node.addMethod("weakOffer", Keyword.PUBLIC);
            patchMethodAsDeprecatedRedirector(deprecatedMethodRedirect, "failFastOffer", PrimitiveType.intType(),
                    new Parameter(classType("E"), "e"));
        }

        node.setJavadocComment(formatMultilineJavadoc(0,
                "NOTE: This class was automatically generated by "
                        + JavaParsingAtomicArrayQueueGenerator.class.getName(),
                "which can found in the jctools-build module. The original source file is " + sourceFileName + ".")
                + node.getJavadocComment().orElse(new JavadocComment("")).getContent());
    }

    String translateQueueName(String originalQueueName) {
        if (originalQueueName.length() < 5) {
            return originalQueueName;
        }

        String start = originalQueueName.substring(0, 4);
        String end = originalQueueName.substring(4);
        if ((start.equals("Spsc") || start.equals("Spmc") || start.equals("Mpsc") || start.equals("Mpmc"))
                && end.startsWith("ArrayQueue")) {
            return start + "Atomic" + end;
        }

        return originalQueueName;
    }

    String fieldUpdaterFieldName(String fieldName) {
        switch (fieldName) {
        case "producerIndex":
            return "P_INDEX_UPDATER";
        case "consumerIndex":
            return "C_INDEX_UPDATER";
        case "producerLimit":
            return "P_LIMIT_UPDATER";
        default:
            throw new IllegalArgumentException("Unhandled field: " + fieldName);
        }
    }

    void organiseImports(CompilationUnit cu) {
        List<ImportDeclaration> importDecls = new ArrayList<>();
        for (ImportDeclaration importDeclaration : cu.getImports()) {
            if (importDeclaration.getNameAsString().startsWith("org.jctools.util.Unsafe")) {
                continue;
            }
            importDecls.add(importDeclaration);
        }
        cu.getImports().clear();
        for (ImportDeclaration importDecl : importDecls) {
            cu.addImport(importDecl);
        }
        cu.addImport(importDeclaration("java.util.concurrent.atomic.AtomicLongFieldUpdater"));
        cu.addImport(importDeclaration("java.util.concurrent.atomic.AtomicReferenceArray"));
        cu.addImport(importDeclaration("java.util.concurrent.atomic.AtomicLongArray"));
        cu.addImport(importDeclaration("org.jctools.queues.MessagePassingQueueUtil"));
    }

    /**
     * Given a variable declaration of some sort, check it's name and type and
     * if it looks like any of the key type changes between unsafe and atomic
     * queues, perform the conversion to change it's type.
     */
    void processSpecialNodeTypes(NodeWithType<?, Type> node, String name) {
        Type type = node.getType();
        if ("buffer".equals(name) && isRefArray(type, "E")) {
            node.setType(atomicRefArrayType((ArrayType) type));
        } else if ("sBuffer".equals(name) && isLongArray(type)) {
            node.setType(atomicLongArrayType());
        } else if (PrimitiveType.longType().equals(type)) {
            switch(name) {
            case "mask":
            case "offset":
            case "seqOffset":
            case "lookAheadSeqOffset":
            case "lookAheadElementOffset":
                node.setType(PrimitiveType.intType());
            }
        }
    }

    /**
     * Searches all extended or implemented super classes or interfaces for
     * special classes that differ with the atomics version and replaces them
     * with the appropriate class.
     */
    private void replaceParentClassesForAtomics(ClassOrInterfaceDeclaration n) {
        for (ClassOrInterfaceType parent : n.getExtendedTypes()) {
            if ("ConcurrentCircularArrayQueue".equals(parent.getNameAsString())) {
                parent.setName("AtomicReferenceArrayQueue");
            } else if ("ConcurrentSequencedCircularArrayQueue".equals(parent.getNameAsString())) {
                parent.setName("SequencedAtomicReferenceArrayQueue");
            } else {
                // Padded super classes are to be renamed and thus so does the
                // class we must extend.
                parent.setName(translateQueueName(parent.getNameAsString()));
            }
        }
    }

    /**
     * Given a method declaration node this method will replace it's code and
     * signature with code to redirect all calls to it to the
     * <code>newMethodName</code>. Method signatures of both methods must match
     * exactly.
     */
    private void patchMethodAsDeprecatedRedirector(MethodDeclaration methodToPatch, String toMethodName,
            Type returnType, Parameter... parameters) {
        methodToPatch.setType(returnType);
        for (Parameter parameter : parameters) {
            methodToPatch.addParameter(parameter);
        }
        methodToPatch.addAnnotation(new MarkerAnnotationExpr("Deprecated"));

        methodToPatch.setJavadocComment(
                formatMultilineJavadoc(1, "@deprecated This was renamed to " + toMethodName + " please migrate"));

        MethodCallExpr methodCall = methodCallExpr("this", toMethodName);
        for (Parameter parameter : parameters) {
            methodCall.addArgument(new NameExpr(parameter.getName()));
        }

        BlockStmt body = new BlockStmt();
        body.addStatement(new ReturnStmt(methodCall));
        methodToPatch.setBody(body);
    }

    /**
     * For each method accessor to a field, add in the calls necessary to
     * AtomicFieldUpdaters. Only methods start with so/cas/sv/lv/lp followed by
     * the field name are processed. Clearly <code>lv<code>, <code>lp<code> and
     * <code>sv<code> are simple field accesses with only <code>so and <code>cas
     * <code> using the AtomicFieldUpdaters.
     *
     * @param n
     *            the AST node for the containing class
     */
    private void patchAtomicFieldUpdaterAccessorMethods(ClassOrInterfaceDeclaration n) {
        String className = n.getNameAsString();

        for (FieldDeclaration field : n.getFields()) {
            if (field.getModifiers().contains(Modifier.staticModifier())) {
                // Ignore statics
                continue;
            }

            boolean usesFieldUpdater = false;
            for (VariableDeclarator variable : field.getVariables()) {
                String variableName = variable.getNameAsString();

                String methodNameSuffix = capitalise(variableName);

                for (MethodDeclaration method : n.getMethods()) {
                    String methodName = method.getNameAsString();
                    if (!methodName.endsWith(methodNameSuffix)) {
                        // Leave it untouched
                        continue;
                    }

                    String newValueName = "newValue";
                    if (methodName.startsWith("so")) {
                        usesFieldUpdater = true;
                        String fieldUpdaterFieldName = fieldUpdaterFieldName(variableName);

                        method.setBody(fieldUpdaterLazySet(fieldUpdaterFieldName, newValueName));
                    } else if (methodName.startsWith("cas")) {
                        usesFieldUpdater = true;
                        String fieldUpdaterFieldName = fieldUpdaterFieldName(variableName);
                        String expectedValueName = "expect";
                        method.setBody(
                                fieldUpdaterCompareAndSet(fieldUpdaterFieldName, expectedValueName, newValueName));
                    } else if (methodName.startsWith("sv")) {
                        method.setBody(fieldAssignment(variableName, newValueName));
                    } else if (methodName.startsWith("lv") || methodName.startsWith("lp")) {
                        method.setBody(returnField(variableName));
                    } else {
                        throw new IllegalStateException("Unhandled method: " + methodName);
                    }
                }

                if (usesFieldUpdater) {
                    n.getMembers().add(0, declareLongFieldUpdater(className, variableName));
                }
            }

            if (usesFieldUpdater) {
                field.addModifier(Keyword.VOLATILE);
            }
        }
    }

    private boolean isLongArray(Type in) {
        if (in instanceof ArrayType) {
            ArrayType aType = (ArrayType) in;
            return PrimitiveType.longType().equals(aType.getComponentType());
        }
        return false;
    }

    private ClassOrInterfaceType atomicRefArrayType(ArrayType in) {
        ClassOrInterfaceType out = new ClassOrInterfaceType(null, "AtomicReferenceArray");
        out.setTypeArguments(in.getComponentType());
        return out;
    }

    private ClassOrInterfaceType atomicLongArrayType() {
        return new ClassOrInterfaceType(null, "AtomicLongArray");
    }

}
