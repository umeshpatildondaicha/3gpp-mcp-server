package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * BootstrapService is pure orchestration: wire the heavy services together in
 * the right order and refuse to come up when index and runtime disagree. Every
 * heavy collaborator is mocked — what's under test is the wiring, the two
 * mismatch gates, and that StartupState always lands in exactly one of
 * 'ready' / 'failed'. A server that comes up ready with a mismatched embedding
 * index returns confidently-wrong vectors forever, which is why the gates get
 * their own tests.
 */
class BootstrapServiceTest {

    private DbResolver dbResolver;
    private KbDataService kbDataService;
    private EmbeddingService embeddingService;
    private RerankService rerankService;
    private ScopeGateService scopeGateService;
    private IntentClassifierService intentClassifier;
    private StartupState startupState;
    private BootstrapService bootstrap;

    private final Path db1 = Path.of("/tmp/primary.db");
    private final Path db2 = Path.of("/tmp/extras.db");

    @BeforeEach
    void setUp() throws Exception {
        dbResolver = mock(DbResolver.class);
        kbDataService = mock(KbDataService.class);
        embeddingService = mock(EmbeddingService.class);
        rerankService = mock(RerankService.class);
        scopeGateService = mock(ScopeGateService.class);
        intentClassifier = mock(IntentClassifierService.class);
        startupState = new StartupState();   // real, cheap — it's the observable output

        // A fully consistent world; individual tests break one thing at a time.
        when(dbResolver.resolveDb()).thenReturn(db1);
        when(dbResolver.resolveDb2()).thenReturn(db2);
        when(kbDataService.allSpecIds()).thenReturn(Set.of("23.501", "38.331"));
        when(kbDataService.embedModelName()).thenReturn("bge-small-en-v1.5");
        when(kbDataService.embedDimFromMeta()).thenReturn(384);
        when(embeddingService.modelName()).thenReturn("bge-small-en-v1.5");
        when(embeddingService.dim()).thenReturn(384);

        bootstrap = new BootstrapService(dbResolver, kbDataService, embeddingService,
                rerankService, scopeGateService, intentClassifier, startupState);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void endsReadyAndWiresEveryService() throws Exception {
            bootstrap.initialize();

            assertTrue(startupState.ready());
            assertEquals("ready", startupState.phase());
            verify(kbDataService).init(List.of(db1, db2), startupState);
            verify(embeddingService).init(startupState);
            verify(rerankService).init(startupState);
            verify(scopeGateService).resolveAgainstIndex(Set.of("23.501", "38.331"));
            verify(intentClassifier).init();
        }

        @Test
        void initialisesInDependencyOrder() throws Exception {
            // The scope gate needs the loaded KB; the intent classifier needs the
            // embedding model. Order is behaviour here, not an implementation detail.
            bootstrap.initialize();

            InOrder order = inOrder(kbDataService, embeddingService,
                    scopeGateService, intentClassifier);
            order.verify(kbDataService).init(List.of(db1, db2), startupState);
            order.verify(embeddingService).init(startupState);
            order.verify(scopeGateService).resolveAgainstIndex(Set.of("23.501", "38.331"));
            order.verify(intentClassifier).init();
        }

        @Test
        void modelNameComparisonIsCaseInsensitive() throws Exception {
            when(kbDataService.embedModelName()).thenReturn("BGE-Small-EN-v1.5");
            bootstrap.initialize();
            assertTrue(startupState.ready());
        }

        @Test
        void nullIndexedModelNameSkipsTheModelGate() throws Exception {
            // Old indexes carry no model name in meta — must not block startup.
            when(kbDataService.embedModelName()).thenReturn(null);
            bootstrap.initialize();
            assertTrue(startupState.ready());
        }

        @Test
        void zeroIndexedDimIsTrustedRatherThanFatal() throws Exception {
            // meta table without an 'embed_dim' key reports 0: warn-and-continue.
            when(kbDataService.embedDimFromMeta()).thenReturn(0);
            bootstrap.initialize();
            assertTrue(startupState.ready());
        }
    }

    @Nested
    @DisplayName("consistency gates refuse startup")
    class ConsistencyGates {

        @Test
        void embeddingModelMismatchFailsStartup() throws Exception {
            when(kbDataService.embedModelName()).thenReturn("all-MiniLM-L6-v2");

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> bootstrap.initialize());

            assertTrue(e.getMessage().contains("Embedding model mismatch"),
                    "unexpected message: " + e.getMessage());
            assertTrue(e.getMessage().contains("all-MiniLM-L6-v2"),
                    "must name the index's model: " + e.getMessage());
            assertTrue(e.getMessage().contains("bge-small-en-v1.5"),
                    "must name the runtime's model: " + e.getMessage());
            assertFalse(startupState.ready());
            assertEquals("failed", startupState.phase());
        }

        @Test
        void embeddingDimensionMismatchFailsStartup() throws Exception {
            when(kbDataService.embedDimFromMeta()).thenReturn(768);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> bootstrap.initialize());

            assertTrue(e.getMessage().contains("dimension mismatch"),
                    "unexpected message: " + e.getMessage());
            assertFalse(startupState.ready());
            assertEquals("failed", startupState.phase());
        }
    }

    @Nested
    @DisplayName("collaborator failures")
    class CollaboratorFailures {

        @Test
        void dbResolutionFailureIsWrappedAndStateIsFailed() throws Exception {
            when(dbResolver.resolveDb())
                    .thenThrow(new IllegalStateException("primary knowledge-base DB not found"));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> bootstrap.initialize());

            assertTrue(e.getMessage().startsWith("Startup failed"),
                    "unexpected message: " + e.getMessage());
            assertTrue(e.getMessage().contains("primary knowledge-base DB not found"),
                    "root cause must surface in the message: " + e.getMessage());
            assertEquals("failed", startupState.phase());
            assertFalse(startupState.ready());
            // Nothing downstream may run once resolution has failed.
            verify(kbDataService, never()).init(org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        void kbLoadFailureIsWrappedWithCausePreserved() throws Exception {
            SQLException boom = new SQLException("chunks table is corrupt");
            org.mockito.Mockito.doThrow(boom)
                    .when(kbDataService).init(List.of(db1, db2), startupState);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> bootstrap.initialize());

            assertEquals(boom, e.getCause(), "original exception must be the cause");
            assertEquals("failed", startupState.phase());
            verify(intentClassifier, never()).init();
        }
    }
}
