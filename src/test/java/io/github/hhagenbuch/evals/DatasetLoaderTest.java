package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.dataset.DatasetLoader;
import io.github.hhagenbuch.evals.model.Dataset;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetLoaderTest {

    @Test
    void loadsSmokeDataset() {
        Dataset dataset = DatasetLoader.load(Path.of("datasets/smoke.yaml"));
        assertThat(dataset.name()).isEqualTo("smoke");
        assertThat(dataset.target()).isEqualTo("echo");
        assertThat(dataset.cases()).hasSize(2);
        assertThat(dataset.cases().get(0).assertions()).hasSize(2);
    }
}
