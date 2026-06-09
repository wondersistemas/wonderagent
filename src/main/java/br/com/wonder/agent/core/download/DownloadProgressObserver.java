package br.com.wonder.agent.core.download;

import com.salesforce.zsync.ZsyncObserver;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Observer zsync que rastreia progresso de download e emite linhas de status
 * via {@code Consumer<String>}.
 *
 * Quando o tamanho total é conhecido: emite uma linha a cada 1% completo.
 * Quando não é conhecido (delta sem Content-Length): apenas {@link #reportFinal()} emite.
 * Em ambos os casos {@link #reportFinal()} emite a linha conclusiva com velocidade média.
 */
public class DownloadProgressObserver extends ZsyncObserver {

    private final Consumer<String> progress;

    private long totalBytes = -1;
    private final AtomicLong downloadedBytes = new AtomicLong(0);

    private long startTime = 0;
    private int lastReportedPct = -1;

    public DownloadProgressObserver(Consumer<String> progress) {
        this.progress = progress;
    }

    @Override
    public void remoteFileDownloadingStarted(URI uri, long contentLength) {
        if (contentLength > 0) {
            totalBytes = contentLength;
        }
        startTime = System.currentTimeMillis();
    }

    @Override
    public void bytesDownloaded(long count) {
        if (totalBytes <= 0) return;

        long downloaded = downloadedBytes.addAndGet(count);
        int pct = (int) Math.min(100, downloaded * 100 / totalBytes);

        if (pct / 5 > lastReportedPct / 5) {
            lastReportedPct = pct;
            long elapsed = System.currentTimeMillis() - startTime;
            double speedKBs = elapsed > 0 ? (downloaded / 1024.0) / (elapsed / 1000.0) : 0;
            progress.accept(buildLine(downloaded, pct, speedKBs));
        }
    }

    /** Emite linha final com totais e velocidade média geral. */
    public void reportFinal() {
        long downloaded = downloadedBytes.get();
        long elapsed = System.currentTimeMillis() - startTime;
        double speedKBs = elapsed > 0 ? (downloaded / 1024.0) / (elapsed / 1000.0) : 0;

        if (totalBytes > 0) {
            progress.accept(buildLine(downloaded, 100, speedKBs));
        } else {
            progress.accept(String.format("  %s transferidos  %s", formatSize(downloaded), formatSpeed(speedKBs)));
        }
    }

    private String buildLine(long downloaded, int pct, double speedKBs) {
        return formatProgress(downloaded, totalBytes, pct, speedKBs);
    }

    static String formatProgress(long downloaded, long total, int pct, double speedKBs) {
        return String.format("  %s / %s (%d%%)  %s",
                formatSize(downloaded), formatSize(total), pct, formatSpeed(speedKBs));
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024L * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }

    private static String formatSpeed(double kbs) {
        if (kbs >= 1024) {
            return String.format("%.1f MB/s", kbs / 1024);
        } else {
            return String.format("%.0f KB/s", kbs);
        }
    }
}
