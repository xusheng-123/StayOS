package com.stayos.integration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background worker (guide section 14: 后台工作器以数据库有序领取/有界重试执行). Each tick claims a
 * bounded batch in database order; claims commit in their own short transaction BEFORE any external
 * call, so no room/inventory lock is ever held across supplier HTTP. Backoff is bounded and dead
 * letters stop at MANUAL for human handling instead of retrying forever.
 */
@Component
public class ChannelOutboxWorker {

    final ChannelService channels;

    public ChannelOutboxWorker(ChannelService channels) { this.channels = channels; }

    @Scheduled(fixedDelayString = "${stayos.channel.worker-interval-ms:5000}", initialDelayString = "${stayos.channel.worker-initial-delay-ms:10000}")
    public void tick() {
        try {
            channels.processOutbox(null, 50);
            channels.processInbound(null, 50);
        } catch (Exception ignored) {
            // The worker never crashes the app on a single bad tick; failures are tracked per event.
        }
    }
}
