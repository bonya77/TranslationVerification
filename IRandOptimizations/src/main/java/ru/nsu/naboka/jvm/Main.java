package ru.nsu.naboka.jvm;

import ru.nsu.naboka.ir.Optimizer;
import ru.nsu.naboka.runtime.ImmutableIntArray;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public final class Main {
    private static final String EXTENSION = ".funny";
    private static final String USAGE = """
            Использование:
              funny compile [--out каталог] [--class имя] [--no-opt] файл.funny
              funny run     [--out каталог] [--class имя] [--no-opt] файл.funny функция [аргументы...]
            Аргументы: число или массив, например '[1, -2]'.""";

    private Main() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (Arrays.equals(args, new String[]{"--help"})) {
            out.println(USAGE);
            return 0;
        }
        Command command;
        try {
            command = parse(args);
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            err.println(USAGE);
            return 2;
        }

        byte[] bytes;
        Path classFile = command.directory().resolve(command.className() + ".class");
        try {
            boolean optimize = command.optimize();
            bytes = FunnyCompiler.compile(Files.readString(command.source()), command.className(),
                    new Optimizer.Options(optimize, optimize, optimize));
            writeClass(classFile, bytes);
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return 1;
        } catch (IOException error) {
            err.println(error.getMessage());
            return 2;
        }

        if (command.function() == null) {
            out.println(classFile);
            return 0;
        }
        try {
            Class<?> module = new ModuleLoader().loadModule(command.className(), bytes);
            Object result = invoke(module, command.function(), command.arguments());
            out.println(result instanceof int[] tuple ? Arrays.toString(tuple) : result);
            return 0;
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return 2;
        } catch (InvocationTargetException error) {
            error.getCause().printStackTrace(err);
            return 3;
        } catch (ReflectiveOperationException | LinkageError error) {
            error.printStackTrace(err);
            return 3;
        }
    }

    private static Command parse(String[] args) {
        Deque<String> remaining = new ArrayDeque<>(List.of(args));
        String action = take(remaining, "требуется команда compile или run");
        if (!action.equals("compile") && !action.equals("run")) {
            throw new IllegalArgumentException("неизвестная команда: " + action);
        }

        Path directory = Path.of(".");
        String className = null;
        boolean optimize = true;
        while (!remaining.isEmpty() && remaining.getFirst().startsWith("--")) {
            String option = remaining.removeFirst();
            switch (option) {
                case "--out" -> directory = Path.of(take(remaining, "для --out нужен каталог"));
                case "--class" -> className = take(remaining, "для --class нужно имя класса");
                case "--no-opt" -> optimize = false;
                default -> throw new IllegalArgumentException("неизвестный параметр: " + option);
            }
        }

        Path source = Path.of(take(remaining, "требуется файл .funny"));
        if (source.getFileName() == null || !source.getFileName().toString().endsWith(EXTENSION)) {
            throw new IllegalArgumentException("требуется файл .funny: " + source);
        }
        if (className == null) className = defaultClassName(source.getFileName().toString());
        FunnyCompiler.checkClassName(className);
        String function = null;
        if (action.equals("run")) function = take(remaining, "требуется имя функции");
        else if (!remaining.isEmpty()) throw new IllegalArgumentException("лишний аргумент: " + remaining.getFirst());
        return new Command(source, directory, className, optimize, function, List.copyOf(remaining));
    }

    private static String take(Deque<String> arguments, String error) {
        if (arguments.isEmpty()) throw new IllegalArgumentException(error);
        return arguments.removeFirst();
    }

    private static String defaultClassName(String fileName) {
        String stem = fileName.substring(0, fileName.length() - EXTENSION.length()).replaceAll("[^A-Za-z0-9_]", "_");
        return stem.isEmpty() || Character.isDigit(stem.charAt(0)) ? "_" + stem : stem;
    }

    private static void writeClass(Path path, byte[] bytes) throws IOException {
        Path directory = path.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".funny-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, path, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Object invoke(Class<?> module, String name, List<String> raw) throws ReflectiveOperationException {
        Method method = Arrays.stream(module.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name) && Modifier.isPublic(candidate.getModifiers()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("неизвестная функция: " + name));
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != raw.size()) {
            throw new IllegalArgumentException("функция " + name + " ожидает аргументов: " + parameters.length
                    + ", передано: " + raw.size());
        }
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String value = raw.get(i).strip();
            try {
                values[i] = parameters[i] == int.class ? Integer.parseInt(value) : parseArray(value);
            } catch (IllegalArgumentException error) {
                String type = parameters[i] == int.class ? "int" : "int[]";
                throw new IllegalArgumentException("аргумент " + (i + 1) + ": ожидался " + type + ", получено " + value);
            }
        }
        return method.invoke(null, values);
    }

    private static ImmutableIntArray parseArray(String value) {
        if (!value.startsWith("[") || !value.endsWith("]")) throw new IllegalArgumentException();
        String contents = value.substring(1, value.length() - 1).strip();
        if (contents.isEmpty()) return ImmutableIntArray.of();
        int[] elements = Arrays.stream(contents.split(",", -1)).map(String::strip).mapToInt(Integer::parseInt).toArray();
        return ImmutableIntArray.of(elements);
    }

    private record Command(Path source, Path directory, String className, boolean optimize,
                           String function, List<String> arguments) {}

    private static final class ModuleLoader extends ClassLoader {
        Class<?> loadModule(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
