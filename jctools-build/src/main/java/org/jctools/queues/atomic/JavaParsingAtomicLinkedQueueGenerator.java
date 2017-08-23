package org.jctools.queues.atomic;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.AssignExpr.Operator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithType;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public final class JavaParsingAtomicLinkedQueueGenerator extends VoidVisitorAdapter<Void> {
    private static final String GEN_DIRECTIVE_CLASS_CONTAINS_ORDERED_FIELD_ACCESSORS = "$gen:ordered-fields";
    private static final String GEN_DIRECTIVE_METHOD_IGNORE = "$gen:ignore";
    private static final String MPSC_LINKED_ATOMIC_QUEUE_NAME = "MpscLinkedAtomicQueue";
    private static final String INDENT_LEVEL = "    ";
    private final String sourceFileName;

    public JavaParsingAtomicLinkedQueueGenerator(String sourceFileName) {
        super();
        this.sourceFileName = sourceFileName;
    }

    @Override
    public void visit(FieldAccessExpr n, Void arg) {
        super.visit(n, arg);
        if (n.getScope() instanceof NameExpr) {
            NameExpr name = (NameExpr) n.getScope();
            name.setName(translateQueueName(name.getNameAsString()));
        }
    }
    
    @Override
    public void visit(CastExpr n, Void arg) {
        super.visit(n, arg);
        
        if (isRefArray(n.getType(), "E")) {
            n.setType(atomicRefArrayType((ArrayType) n.getType()));
        }
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration node, Void arg) {
        super.visit(node, arg);

        replaceParentClassesForAtomics(node);

        node.setName(translateQueueName(node.getNameAsString()));
        if (MPSC_LINKED_ATOMIC_QUEUE_NAME.equals(node.getNameAsString())) {
            /*
             * Special case for MPSC
             */
            node.removeModifier(Modifier.ABSTRACT);
            node.addModifier(Modifier.FINAL);
        }

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

        node.setJavadocComment(formatMultilineJavadoc(0,
                "NOTE: This class was automatically generated by "
                        + JavaParsingAtomicLinkedQueueGenerator.class.getName(),
                "which can found in the jctools-build module. The original source file is " + sourceFileName + ".")
                + node.getJavadocComment().orElse(new JavadocComment("")).getContent());
    }

    @Override
    public void visit(ConstructorDeclaration n, Void arg) {
        super.visit(n, arg);
        // Update the ctor to match the class name
        n.setName(translateQueueName(n.getNameAsString()));
        if (MPSC_LINKED_ATOMIC_QUEUE_NAME.equals(n.getNameAsString())) {
            // Special case for MPSC because the Unsafe variant has a static factory method and a protected constructor.
            n.setModifier(Modifier.PROTECTED, false);
            n.setModifier(Modifier.PUBLIC, true);
        }
    }

    @Override
    public void visit(PackageDeclaration n, Void arg) {
        super.visit(n, arg);
        // Change the package of the output
        n.setName("org.jctools.queues.atomic");
    }

    @Override
    public void visit(Parameter n, Void arg) {
        super.visit(n, arg);
        // Process parameters to methods and ctors
        processSpecialNodeTypes(n);
    }

    @Override
    public void visit(VariableDeclarator n, Void arg) {
        super.visit(n, arg);
        // Replace declared variables with altered types
        processSpecialNodeTypes(n);
    }

    @Override
    public void visit(MethodDeclaration n, Void arg) {
        super.visit(n, arg);
        // Replace the return type of a method with altered types
        processSpecialNodeTypes(n);
    }

    @Override
    public void visit(ObjectCreationExpr n, Void arg) {
        super.visit(n, arg);
        processSpecialNodeTypes(n);
    }

    private static boolean isCommentPresent(Node node, String wanted) {
        Optional<Comment> maybeComment = node.getComment();
        if (maybeComment.isPresent()) {
            Comment comment = maybeComment.get();
            String content = comment.getContent().trim();
            if (wanted.equals(content)) {
                return true;
            }
        }
        return false;
    }

    private static void removeStaticFieldsAndInitialisers(ClassOrInterfaceDeclaration node) {
        // Remove all the static initialisers
        for (InitializerDeclaration child : node.getChildNodesByType(InitializerDeclaration.class)) {
            child.remove();
        }

        // Remove all static fields
        for (FieldDeclaration field : node.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
                field.remove();
                continue;
            }
        }
    }

    /**
     * Searches all extended or implemented super classes or interfaces for
     * special classes that differ with the atomics version and replaces them
     * with the appropriate class.
     * 
     * @param n
     */
    private static void replaceParentClassesForAtomics(ClassOrInterfaceDeclaration n) {
        replaceParentClassesForAtomics(n.getExtendedTypes());
        replaceParentClassesForAtomics(n.getImplementedTypes());
    }

    private static void replaceParentClassesForAtomics(NodeList<ClassOrInterfaceType> types) {
        for (ClassOrInterfaceType parent : types) {
            if ("BaseLinkedQueue".equals(parent.getNameAsString())) {
                parent.setName("BaseLinkedAtomicQueue");
            } else {
                // Padded super classes are to be renamed and thus so does the
                // class we must extend.
                parent.setName(translateQueueName(parent.getNameAsString()));
            }
        }
    }

    /**
     * For each method accessor to a field, add in the calls necessary to
     * AtomicFieldUpdaters. Only methods start with so/cas/sv/lv/lp/sp/xchg
     * followed by the field name are processed. Clearly <code>lv<code>,
     * <code>lp<code> and <code>sv<code> are simple field accesses with only
     * <code>so and <code>cas <code> using the AtomicFieldUpdaters.
     * 
     * @param n
     *            the AST node for the containing class
     */
    private static void patchAtomicFieldUpdaterAccessorMethods(ClassOrInterfaceDeclaration n) {
        String className = n.getNameAsString();

        for (FieldDeclaration field : n.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
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
                    if (methodName.startsWith("so") || methodName.startsWith("sp")) {
                        /*
                         * In the case of 'sp' use lazySet as the weakest
                         * ordering allowed by field updaters
                         */
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

                if ("producerNode".equals(variableName)) {
                    usesFieldUpdater = true;
                    String fieldUpdaterFieldName = fieldUpdaterFieldName(variableName);

                    MethodDeclaration method = n.addMethod("xchgProducerNode", Modifier.PROTECTED, Modifier.FINAL);
                    method.setType(simpleParametricType("LinkedQueueAtomicNode", "E"));
                    method.addParameter(simpleParametricType("LinkedQueueAtomicNode", "E"), "newValue");
                    method.setBody(fieldUpdaterGetAndSet(fieldUpdaterFieldName, "newValue"));
                }

                if (usesFieldUpdater) {
                    if (PrimitiveType.longType().equals(variable.getType())) {
                        n.getMembers().add(0, declareLongFieldUpdater(className, variableName));
                    } else {
                        n.getMembers().add(0, declareRefFieldUpdater(className, variableName));
                    }
                }
            }

            if (usesFieldUpdater) {
                field.addModifier(Modifier.VOLATILE);
            }
        }
    }

    private static String capitalise(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String formatMultilineJavadoc(int indent, String... lines) {
        String indentation = "";
        for (int i = 0; i < indent; i++) {
            indentation += INDENT_LEVEL;
        }

        String out = "\n";
        for (String line : lines) {
            out += indentation + " * " + line + "\n";
        }
        out += indentation + " ";
        return out;
    }

    /**
     * Generates something like
     * <code>P_INDEX_UPDATER.lazySet(this, newValue)</code>
     * 
     * @param fieldUpdaterFieldName
     * @param newValueName
     * @return
     */
    private static BlockStmt fieldUpdaterLazySet(String fieldUpdaterFieldName, String newValueName) {
        BlockStmt body = new BlockStmt();
        body.addStatement(new ExpressionStmt(
                methodCallExpr(fieldUpdaterFieldName, "lazySet", new ThisExpr(), new NameExpr(newValueName))));
        return body;
    }

    /**
     * Generates something like
     * <code>return P_INDEX_UPDATER.compareAndSet(this, expectedValue, newValue)</code>
     * 
     * @param fieldUpdaterFieldName
     * @param expectedValueName
     * @param newValueName
     * @return
     */
    private static BlockStmt fieldUpdaterCompareAndSet(String fieldUpdaterFieldName, String expectedValueName,
            String newValueName) {
        BlockStmt body = new BlockStmt();
        body.addStatement(new ReturnStmt(methodCallExpr(fieldUpdaterFieldName, "compareAndSet", new ThisExpr(),
                new NameExpr(expectedValueName), new NameExpr(newValueName))));
        return body;
    }

    /**
     * Generates something like
     * <code>return P_INDEX_UPDATER.getAndSet(this, newValue)</code>
     * 
     * @param fieldUpdaterFieldName
     * @param newValueName
     * @return
     */
    private static BlockStmt fieldUpdaterGetAndSet(String fieldUpdaterFieldName, String newValueName) {
        BlockStmt body = new BlockStmt();
        body.addStatement(new ReturnStmt(
                methodCallExpr(fieldUpdaterFieldName, "getAndSet", new ThisExpr(), new NameExpr(newValueName))));
        return body;
    }

    /**
     * Generates something like <code>field = newValue</code>
     * 
     * @param fieldName
     * @param valueName
     * @return
     */
    private static BlockStmt fieldAssignment(String fieldName, String valueName) {
        BlockStmt body = new BlockStmt();
        body.addStatement(
                new ExpressionStmt(new AssignExpr(new NameExpr(fieldName), new NameExpr(valueName), Operator.ASSIGN)));
        return body;
    }

    /**
     * Generates something like
     * <code>private static final AtomicLongFieldUpdater<MpmcAtomicArrayQueueProducerIndexField> P_INDEX_UPDATER = AtomicLongFieldUpdater.newUpdater(MpmcAtomicArrayQueueProducerIndexField.class, "producerIndex");</code>
     * 
     * @param type
     * @param name
     * @param initializer
     * @param modifiers
     * @return
     */
    private static FieldDeclaration fieldDeclarationWithInitialiser(Type type, String name, Expression initializer,
            Modifier... modifiers) {
        FieldDeclaration fieldDeclaration = new FieldDeclaration();
        VariableDeclarator variable = new VariableDeclarator(type, name, initializer);
        fieldDeclaration.getVariables().add(variable);
        EnumSet<Modifier> modifierSet = EnumSet.copyOf(Arrays.asList(modifiers));
        fieldDeclaration.setModifiers(modifierSet);
        return fieldDeclaration;
    }

    /**
     * Generates something like
     * <code>private static final AtomicReferenceFieldUpdater<MpmcAtomicArrayQueueProducerNodeField> P_NODE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(MpmcAtomicArrayQueueProducerNodeField.class, "producerNode");</code>
     * 
     * @param className
     * @param variableName
     * @return
     */
    private static FieldDeclaration declareRefFieldUpdater(String className, String variableName) {
        MethodCallExpr initializer = newAtomicRefFieldUpdater(className, variableName);

        ClassOrInterfaceType type = simpleParametricType("AtomicReferenceFieldUpdater", className,
                "LinkedQueueAtomicNode");
        FieldDeclaration newField = fieldDeclarationWithInitialiser(type, fieldUpdaterFieldName(variableName),
                initializer, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        return newField;
    }

    private static MethodCallExpr newAtomicRefFieldUpdater(String className, String variableName) {
        return methodCallExpr("AtomicReferenceFieldUpdater", "newUpdater", new ClassExpr(classType(className)),
                new ClassExpr(classType("LinkedQueueAtomicNode")), new StringLiteralExpr(variableName));
    }

    /**
     * Generates something like
     * <code>private static final AtomicLongFieldUpdater<MpmcAtomicArrayQueueProducerIndexField> P_INDEX_UPDATER = AtomicLongFieldUpdater.newUpdater(MpmcAtomicArrayQueueProducerIndexField.class, "producerIndex");</code>
     * 
     * @param className
     * @param variableName
     * @return
     */
    private static FieldDeclaration declareLongFieldUpdater(String className, String variableName) {
        MethodCallExpr initializer = newAtomicLongFieldUpdater(className, variableName);

        ClassOrInterfaceType type = simpleParametricType("AtomicLongFieldUpdater", className);
        FieldDeclaration newField = fieldDeclarationWithInitialiser(type, fieldUpdaterFieldName(variableName),
                initializer, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        return newField;
    }

    private static MethodCallExpr newAtomicLongFieldUpdater(String className, String variableName) {
        return methodCallExpr("AtomicLongFieldUpdater", "newUpdater", new ClassExpr(classType(className)),
                new StringLiteralExpr(variableName));
    }

    /**
     * Generates something like <code>return field</code>
     * 
     * @param fieldName
     * @return
     */
    private static BlockStmt returnField(String fieldName) {
        BlockStmt body = new BlockStmt();
        body.addStatement(new ReturnStmt(fieldName));
        return body;
    }

    private static boolean isRefArray(Type in, String refClassName) {
        if (in instanceof ArrayType) {
            ArrayType aType = (ArrayType) in;
            return isRefType(aType.getComponentType(), refClassName);
        }
        return false;
    }

    private static boolean isRefType(Type in, String className) {
        // Does not check type parameters
        if (in instanceof ClassOrInterfaceType) {
            return (className.equals(((ClassOrInterfaceType) in).getNameAsString()));
        }
        return false;
    }

    private static ClassOrInterfaceType atomicRefArrayType(ArrayType in) {
        ClassOrInterfaceType out = new ClassOrInterfaceType(null, "AtomicReferenceArray");
        out.setTypeArguments(in.getComponentType());
        return out;
    }

    private static MethodCallExpr methodCallExpr(String owner, String method, Expression... args) {
        MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(owner), method);
        for (Expression expr : args) {
            methodCallExpr.addArgument(expr);
        }
        return methodCallExpr;
    }

    private static ClassOrInterfaceType simpleParametricType(String className, String... typeArgs) {
        NodeList<Type> typeArguments = new NodeList<Type>();
        for (String typeArg : typeArgs) {
            typeArguments.add(classType(typeArg));
        }
        return new ClassOrInterfaceType(null, new SimpleName(className), typeArguments);
    }

    private static ClassOrInterfaceType classType(String className) {
        return new ClassOrInterfaceType(null, className);
    }

    private static ImportDeclaration importDeclaration(String name) {
        return new ImportDeclaration(new Name(name), false, false);
    }

    private static ImportDeclaration staticImportDeclaration(String name) {
        return new ImportDeclaration(new Name(name), true, false);
    }

    private static String translateQueueName(String originalQueueName) {
        if (originalQueueName.length() < 5) {
            return originalQueueName;
        }
        
        if (originalQueueName.contains("LinkedQueue") || originalQueueName.contains("LinkedArrayQueue")) {
            return originalQueueName.replace("Linked", "LinkedAtomic");
        }
        
        if (originalQueueName.contains("ArrayQueue")) {
            return originalQueueName.replace("ArrayQueue", "AtomicArrayQueue");
        }

        return originalQueueName;
    }

    private static String fieldUpdaterFieldName(String fieldName) {
        switch (fieldName) {
        case "producerNode":
            return "P_NODE_UPDATER";
        case "consumerNode":
            return "C_NODE_UPDATER";
        case "consumerIndex":
            return "C_INDEX_UPDATER";
        case "producerIndex":
            return "P_INDEX_UPDATER";
        case "producerLimit":
            return "P_LIMIT_UPDATER";
        default:
            throw new IllegalArgumentException("Unhandled field: " + fieldName);
        }
    }

    private static void organiseImports(CompilationUnit cu) {
        List<ImportDeclaration> importDecls = new ArrayList<>();
        for (ImportDeclaration importDeclaration : cu.getImports()) {
            String name = importDeclaration.getNameAsString();
            if (name.startsWith("org.jctools.util.Unsafe")) {
                continue;
            }
            if (name.startsWith("org.jctools.queues.CircularArrayOffsetCalculator")) {
                continue;
            }
            
            if (name.startsWith("org.jctools.queues.LinkedArrayQueueUtil")) {
                importDeclaration.setName(name.replace("org.jctools.queues.LinkedArrayQueueUtil", "org.jctools.queues.atomic.LinkedAtomicArrayQueueUtil"));
            }
            
            importDecls.add(importDeclaration);
        }
        cu.getImports().clear();
        for (ImportDeclaration importDecl : importDecls) {
            cu.addImport(importDecl);
        }
        cu.addImport(importDeclaration("java.util.concurrent.atomic.AtomicReferenceFieldUpdater"));
        cu.addImport(importDeclaration("java.util.concurrent.atomic.AtomicLongFieldUpdater"));
        cu.addImport(importDeclaration("org.jctools.queues.MessagePassingQueue"));
        cu.addImport(importDeclaration("org.jctools.queues.MessagePassingQueue.Supplier"));
        cu.addImport(importDeclaration("org.jctools.queues.MessagePassingQueueUtil"));
        cu.addImport(importDeclaration("org.jctools.queues.QueueProgressIndicators"));
        cu.addImport(importDeclaration("org.jctools.queues.IndexedQueueSizeUtil"));
        cu.addImport(staticImportDeclaration("org.jctools.queues.atomic.LinkedAtomicArrayQueueUtil.*"));
        cu.addImport(importDeclaration("java.util.concurrent.atomic.AtomicReferenceArray"));
        cu.addImport(importDeclaration("org.jctools.queues.MpmcArrayQueue"));
    }

    private static void processSpecialNodeTypes(Parameter node) {
        processSpecialNodeTypes(node, node.getNameAsString());
    }

    private static void processSpecialNodeTypes(VariableDeclarator node) {
        processSpecialNodeTypes(node, node.getNameAsString());
    }

    private static void processSpecialNodeTypes(MethodDeclaration node) {
        processSpecialNodeTypes(node, node.getNameAsString());
    }

    private static void processSpecialNodeTypes(ObjectCreationExpr node) {
        Type type = node.getType();
        if (isRefType(type, "LinkedQueueNode")) {
            node.setType(simpleParametricType("LinkedQueueAtomicNode", "E"));
        } else if (isRefArray(type, "E")) {
            node.setType(atomicRefArrayType((ArrayType) type));
        }
    }

    /**
     * Given a variable declaration of some sort, check it's name and type and
     * if it looks like any of the key type changes between unsafe and atomic
     * queues, perform the conversion to change it's type.
     * 
     * @param node
     * @param name
     */
    private static void processSpecialNodeTypes(NodeWithType<?, Type> node, String name) {
        Type type = node.getType();
        if (node instanceof MethodDeclaration && ("newBufferAndOffset".equals(name) || "nextArrayOffset".equals(name))) {
            node.setType(PrimitiveType.intType());
        } else if (PrimitiveType.longType().equals(type)) {
            switch(name) {
            case "offset":
            case "offsetInNew":
            case "offsetInOld":
            case "lookAheadElementOffset":
                node.setType(PrimitiveType.intType());
            }
        } else if (isRefType(type, "LinkedQueueNode")) {
            node.setType(simpleParametricType("LinkedQueueAtomicNode", "E"));
        } else if (isRefArray(type, "E")) {
            node.setType(atomicRefArrayType((ArrayType) type));
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: outputDirectory inputSourceFiles");
        }

        File outputDirectory = new File(args[0]);

        for (int i = 1; i < args.length; i++) {
            File file = new File(args[i]);
            System.out.println("Processing " + file);
            CompilationUnit cu = JavaParser.parse(file);
            new JavaParsingAtomicLinkedQueueGenerator(file.getName()).visit(cu, null);

            organiseImports(cu);
            FileWriter writer = null;

            String outputFileName = file.getName();
            if (outputFileName.endsWith(".java")) {
                outputFileName = translateQueueName(outputFileName.replace(".java", ""));
            } else {
                outputFileName = translateQueueName(outputFileName);
            }
            outputFileName += ".java";

            File outputFile = new File(outputDirectory, outputFileName);
            try {
                writer = new FileWriter(outputFile);
                writer.write(cu.toString());
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
            System.out.println("Saved to " + outputFile);
        }
    }

}
