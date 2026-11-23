package ru.nsu.naboka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) {
        boolean parseOnly = args.length > 0 && args[0].equals("--parse-only");
        int fileIndex = parseOnly ? 1 : 0;
        if (args.length > fileIndex + 1 || args.length > fileIndex && args[fileIndex].startsWith("--")) {
            System.err.println("Использование: java ru.nsu.naboka.Main [--parse-only] [файл.funny]");
            System.exit(2);
        }
        try {
            String source = args.length == fileIndex
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of(args[fileIndex]), StandardCharsets.UTF_8);
            Parser.Result result = new Parser(source).parse();
            result.errors().forEach(System.err::println);
            if (parseOnly || !result.errors().isEmpty()) {
                System.out.println(AstPrinter.print(result.module()));
            } else {
                SemanticAnalyzer.Result checked = SemanticAnalyzer.analyze(result.module());
                System.out.println(AstPrinter.print(checked));
                checked.diagnostics().forEach(System.err::println);
                if (!checked.diagnostics().isEmpty()) System.exit(1);
            }
            if (!result.errors().isEmpty()) System.exit(1);
        } catch (IOException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }
}
