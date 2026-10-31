package com.stayos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationStartupProbeTest {
    Process running(){var process=mock(Process.class);when(process.isAlive()).thenReturn(true);return process;}
    @Test void requiresDatabaseReadinessRatherThanLiveness()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var probes=new AtomicInteger();var liveness=new AtomicInteger();
        server.createContext("/api/health",exchange->{liveness.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/api/health/ready",exchange->{exchange.sendResponseHeaders(probes.incrementAndGet()<3?503:200,-1);exchange.close();});
        server.start();
        try {SystemAcceptanceTest.ready(running(),server.getAddress().getPort(),Duration.ofSeconds(5));assertThat(probes.get()).isEqualTo(3);assertThat(liveness.get()).isZero();}
        finally {server.stop(0);}
    }
    @Test void rejectsLivenessWhenDatabaseRemainsUnavailable()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var probes=new AtomicInteger();
        server.createContext("/api/health",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/api/health/ready",exchange->{probes.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});
        server.start();
        try {assertThatThrownBy(()->SystemAcceptanceTest.ready(running(),server.getAddress().getPort(),Duration.ofSeconds(1))).isInstanceOf(AssertionError.class).hasMessageContaining("startup timeout").hasMessageContaining("status=503");assertThat(probes.get()).isPositive();}
        finally {server.stop(0);}
    }
    @Test void limitsAnUnresponsiveProbeToTheRemainingBudget()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new CountDownLatch(1);var process=running();
        server.createContext("/api/health/ready",exchange->{try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally {exchange.close();}});
        server.start();
        try {long started=System.nanoTime();assertThatThrownBy(()->SystemAcceptanceTest.ready(process,server.getAddress().getPort(),Duration.ofMillis(500))).isInstanceOf(AssertionError.class).hasMessageContaining("startup timeout");assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));}
        finally {release.countDown();server.stop(0);}
    }
    @Test void failsImmediatelyWhenTheChildHasExited() {
        var process=mock(Process.class);when(process.isAlive()).thenReturn(false);
        assertThatThrownBy(()->SystemAcceptanceTest.ready(process,1,Duration.ofSeconds(1))).isInstanceOf(AssertionError.class).hasMessageContaining("Isolated application stopped");
    }
}
