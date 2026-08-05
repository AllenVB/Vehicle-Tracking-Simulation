package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.VehicleMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Weekly retention for messages: every message (text and voice) is kept {@link #RETENTION_DAYS}
 * days, then its row and — for voice — its audio file are removed. Files are deleted before rows,
 * so a ref is never orphaned. This bounds both the message table and the audio volume, keeping
 * voice messaging cheap. Runs daily at 03:30.
 */
@Component
public class AudioRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AudioRetentionJob.class);
    static final int RETENTION_DAYS = 7;

    private final VehicleMessageRepository messages;
    private final AudioStore audio;

    public AudioRetentionJob(VehicleMessageRepository messages, AudioStore audio) {
        this.messages = messages;
        this.audio = audio;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        List<String> refs = messages.audioRefsOlderThan(RETENTION_DAYS);
        refs.forEach(audio::delete);                         // dosyalar önce
        int rows = messages.deleteOlderThan(RETENTION_DAYS); // sonra satırlar
        if (rows > 0 || !refs.isEmpty()) {
            log.info("Mesaj retention: {} satır, {} ses dosyası silindi ({} günden eski)",
                    rows, refs.size(), RETENTION_DAYS);
        }
    }
}
