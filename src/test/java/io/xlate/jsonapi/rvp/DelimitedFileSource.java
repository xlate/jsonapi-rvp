package io.xlate.jsonapi.rvp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.support.AnnotationConsumer;
import org.junit.jupiter.params.support.ParameterDeclarations;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ArgumentsSource(DelimitedFileProvider.class)
public @interface DelimitedFileSource {

    char delimiter();

    String lineSeparator();

    String[] files();

    char commentCharacter() default '#';
}

class DelimitedFileProvider implements ArgumentsProvider, AnnotationConsumer<DelimitedFileSource> {

    private DelimitedFileSource source;

    @Override
    public void accept(DelimitedFileSource annotation) {
        source = annotation;
    }

    @Override
    public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
        return Arrays.stream(source.files())
            .flatMap(file -> {
                try (var stream = getClass().getClassLoader().getResourceAsStream(file)) {
                    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    return Arrays.stream(text.split(source.lineSeparator()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })
            .filter(Predicate.not(String::isBlank))
            .filter(rec -> !rec.startsWith(String.valueOf(source.commentCharacter())))
            .map(rec -> rec.split(Pattern.quote(String.valueOf(source.delimiter()))))
            .map(rec -> {
                List<Object> args = new ArrayList<>(rec.length);

                for (int p = 0; p < rec.length; p++) {
                    var element = rec[p].trim();
                    int e = p;
                    var paramType = parameters.get(p)
                            .orElseThrow(() -> new IllegalStateException(
                                "File source has element " + e + ", no such parameter index on test method"
                            ))
                            .getParameterType();

                    if (element.isEmpty()) {
                        args.add(null);
                    } else if (paramType == String.class) {
                        args.add(element);
                    } else if (paramType == int.class) {
                        args.add(Integer.parseInt(element));
                    } else if (paramType == Integer.class) {
                        args.add(Integer.valueOf(element));
                    } else {
                        throw new IllegalArgumentException("Unhandled type: " + paramType);
                    }
                }
                return Arguments.from(args);
            });
    }
}