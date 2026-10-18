package ru.nsu.naboka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Использование: java ru.nsu.naboka.Main [файл.funny]");
            System.exit(2);
        }
        try {
            String source = args.length == 0
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
            Parser.Result result = new Parser(source).parse();
            System.out.println(AstPrinter.print(result.module()));
            result.errors().forEach(System.err::println);
            if (!result.errors().isEmpty()) System.exit(1);
        } catch (IOException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }
}
