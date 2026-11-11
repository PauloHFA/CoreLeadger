package com.coreledger.domain;

import com.coreledger.domain.service.LedgerTransferService;
import com.coreledger.domain.service.TransferOrchestrator;
import com.coreledger.infrastructure.acquirer.AcquirerGatewayClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferOrchestratorTest {

    private LedgerTransferService transferService;
    private RedissonClient redisson;
    private AcquirerGatewayClient acquirer;
    private SimpleMeterRegistry meterRegistry;
    private ObjectProvider tracerProvider;
    private TransferOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        transferService = mock(LedgerTransferService.class);
        redisson = mock(RedissonClient.class);
        acquirer = mock(AcquirerGatewayClient.class);
        meterRegistry = new SimpleMeterRegistry();
        tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(null);

        RLock lock1 = mock(RLock.class);
        when(lock1.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock1.isHeldByCurrentThread()).thenReturn(true);

        when(redisson.getLock(anyString())).thenReturn(lock1);

        orchestrator = new TransferOrchestrator(
                transferService,
                redisson,
                acquirer,
                meterRegistry,
                tracerProvider,
                5,
                10
        );
    }

    @Test
    @DisplayName("Executa transferência interna adquirindo travas e chamando o serviço")
    void internalTransferSuccess() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        when(transferService.executeTransfer(eq("key-1"), eq(source), eq(target), eq(5_000L), any(), any()))
                .thenReturn(txId);

        UUID result = orchestrator.transfer("key-1", source, target, 5_000, "Teste", TransferOrchestrator.Type.INTERNAL);

        assertThat(result).isEqualTo(txId);
        verifyNoInteractions(acquirer);
        verify(transferService).executeTransfer(eq("key-1"), eq(source), eq(target), eq(5_000L), any(), any());
    }

    @Test
    @DisplayName("Executa transferência externa autorizando na adquirente antes da liquidação")
    void externalTransferCallsAcquirer() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        when(transferService.executeTransfer(eq("key-2"), eq(source), eq(target), eq(10_000L), any(), any()))
                .thenReturn(txId);

        UUID result = orchestrator.transfer("key-2", source, target, 10_000, "Cartão", TransferOrchestrator.Type.EXTERNAL);

        assertThat(result).isEqualTo(txId);
        verify(acquirer).authorizeWithFallback("key-2", 10_000L);
    }

    @Test
    @DisplayName("Executa retentativa em caso de conflito de concorrência / colisão otimista")
    void retriesOnConcurrencyFailure() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        when(transferService.executeTransfer(eq("key-3"), eq(source), eq(target), eq(2_000L), any(), any()))
                .thenThrow(new ConcurrencyFailureException("Optimistic lock conflict"))
                .thenReturn(txId);

        UUID result = orchestrator.transfer("key-3", source, target, 2_000, "Retry", TransferOrchestrator.Type.INTERNAL);

        assertThat(result).isEqualTo(txId);
        verify(transferService, times(2)).executeTransfer(eq("key-3"), eq(source), eq(target), eq(2_000L), any(), any());
        assertThat(meterRegistry.get("ledger.optimistic.retries").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Falha ao obter trava no Redis lança ConcurrencyFailureException")
    void failsWhenLockCannotBeAcquired() throws InterruptedException {
        RLock failingLock = mock(RLock.class);
        when(failingLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(redisson.getLock(anyString())).thenReturn(failingLock);

        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        assertThatThrownBy(() -> orchestrator.transfer("key-4", source, target, 1_000, "Fail", TransferOrchestrator.Type.INTERNAL))
                .isInstanceOf(ConcurrencyFailureException.class)
                .hasMessageContaining("alta concorrência");

        verifyNoInteractions(transferService);
    }
}
