package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * {@link EmbeddingService} unit tests with the model mocked out — the real
 * BGE-M3 ONNX model never loads. Under test: the blank/null guard (which must
 * not touch the model at all), L2 normalisation of model output, the init()
 * dimension check, and the accessors.
 */
class EmbeddingServiceTest {

    private static final int DIM = 4;

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final EmbeddingService service = new EmbeddingService(model, "test-model", DIM);

    // ── accessors ────────────────────────────────────────────────────────────

    @Test
    void accessorsExposeTheConfiguredNameAndDimension() {
        assertThat(service.modelName()).isEqualTo("test-model");
        assertThat(service.dim()).isEqualTo(DIM);
    }

    // ── embed() guard clauses ────────────────────────────────────────────────

    @Nested
    class BlankInputGuard {

        @Test
        void nullTextYieldsAZeroVectorWithoutCallingTheModel() {
            float[] v = service.embed(null);
            assertThat(v).hasSize(DIM).containsOnly(0f);
            verifyNoInteractions(model);
        }

        @Test
        void blankTextYieldsAZeroVectorWithoutCallingTheModel() {
            float[] v = service.embed("   \t  ");
            assertThat(v).hasSize(DIM).containsOnly(0f);
            verifyNoInteractions(model);
        }
    }

    // ── embed() normalisation ────────────────────────────────────────────────

    @Nested
    class Normalisation {

        @Test
        void modelOutputIsL2Normalised() {
            when(model.embed("hello")).thenReturn(new float[]{3f, 4f, 0f, 0f});

            float[] v = service.embed("hello");

            assertThat(v[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(v[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
            double norm = 0;
            for (float x : v) norm += x * x;
            assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        void allZeroModelOutputIsReturnedUnchangedNotDividedByZero() {
            when(model.embed("void")).thenReturn(new float[]{0f, 0f, 0f, 0f});

            assertThat(service.embed("void")).containsOnly(0f);
        }

        @Test
        void alreadyUnitVectorsSurviveUnchanged() {
            when(model.embed("unit")).thenReturn(new float[]{0f, 1f, 0f, 0f});

            assertThat(service.embed("unit")).containsExactly(0f, 1f, 0f, 0f);
        }
    }

    // ── init() dimension check ───────────────────────────────────────────────

    @Nested
    class Init {

        @Test
        void successfulWarmupSetsTheStartupPhase() {
            when(model.embed("warmup")).thenReturn(new float[]{1f, 0f, 0f, 0f});
            StartupState state = new StartupState();

            service.init(state);

            assertThat(state.phase()).isEqualTo("loading-model");
        }

        @Test
        void dimensionMismatchFailsFastWithBothNumbersInTheMessage() {
            // Model produces 2 floats but the service is configured for 4 —
            // a silent mismatch here would corrupt every stored vector lookup.
            when(model.embed("warmup")).thenReturn(new float[]{1f, 0f});
            StartupState state = new StartupState();

            assertThatThrownBy(() -> service.init(state))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("embed-dim=" + DIM)
                    .hasMessageContaining("produced 2");
        }
    }
}
