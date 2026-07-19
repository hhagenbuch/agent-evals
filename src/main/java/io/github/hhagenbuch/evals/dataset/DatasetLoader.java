package io.github.hhagenbuch.evals.dataset;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hhagenbuch.evals.model.Dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class DatasetLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DatasetLoader() {
    }

    public static Dataset load(Path path) {
        try {
            return YAML.readValue(path.toFile(), Dataset.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load dataset " + path, e);
        }
    }
}
