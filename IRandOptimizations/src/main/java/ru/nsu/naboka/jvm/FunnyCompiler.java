package ru.nsu.naboka.jvm;

import ru.nsu.naboka.Ast;
import ru.nsu.naboka.Parser;
import ru.nsu.naboka.SemanticAnalyzer;
import ru.nsu.naboka.ir.Ir;
import ru.nsu.naboka.ir.IrGenerator;
import ru.nsu.naboka.ir.Optimizer;

import java.util.HashSet;
import java.util.Set;

import static ru.nsu.naboka.Types.Basic.INT_ARRAY;

public final class FunnyCompiler {
    // У статического метода все поддерживаемые параметры занимают по одному слоту JVM.
    private static final int MAX_PARAMETERS = 255;

    private FunnyCompiler() {}

    public static final class CompilationException extends IllegalArgumentException {
        private CompilationException(String message) {
            super(message);
        }
    }

    public static byte[] compile(String source, String className, Optimizer.Options options) {
        checkClassName(className);
        var parsed = new Parser(source).parse();
        if (!parsed.errors().isEmpty()) {
            throw new CompilationException(String.join("\n", parsed.errors()));
        }
        var checked = SemanticAnalyzer.analyze(parsed.module());
        if (!checked.diagnostics().isEmpty()) {
            throw new CompilationException(String.join("\n",
                    checked.diagnostics().stream().map(Object::toString).toList()));
        }

        Set<String> publicMethods = new HashSet<>();
        for (Ast.Node declaration : parsed.module().declarations()) {
            if (declaration instanceof Ast.Function function) {
                checkTupleAbi(function, checked);
                publicMethods.add(function.name());
            }
        }
        var functions = IrGenerator.generate(checked).stream()
                .map(function -> {
                    checkParameterLimit(function, checked);
                    return Optimizer.optimize(function, options);
                })
                .toList();
        return new BytecodeEmitter(className, functions).emit(publicMethods);
    }

    static void checkClassName(String className) {
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("неверное имя JVM-класса: " + className);
        }
    }

    private static void checkTupleAbi(Ast.Function function, SemanticAnalyzer.Result checked) {
        if (function.returns().size() == 1) return;
        for (Ast.Variable result : function.returns()) {
            if (checked.typeOf(result) == INT_ARRAY) {
                throw new CompilationException(result.token().location()
                        + ": [JVM_TUPLE_TYPE] при нескольких результатах каждый должен иметь тип int");
            }
        }
    }

    private static void checkParameterLimit(Ir.Function function, SemanticAnalyzer.Result checked) {
        if (function.parameters().size() <= MAX_PARAMETERS) return;
        var declaration = checked.symbols().stream()
                .filter(symbol -> symbol.kind() == SemanticAnalyzer.Kind.FUNCTION || symbol.kind() == SemanticAnalyzer.Kind.FORMULA)
                .filter(symbol -> symbol.name().equals(function.name()))
                .findFirst().orElseThrow();
        throw new CompilationException(declaration.token().location()
                + ": [JVM_PARAMETER_LIMIT] метод JVM поддерживает до " + MAX_PARAMETERS + " параметров");
    }
}
