/*
 * 合成したWAV音声をMinecraftのストリーミング音声として再生し、再生完了と取り消しを管理する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.speech.PcmAudio;
import jp.voicevoxchat.speech.SpeechService;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** PCM音声をMinecraftのストリーミング音声APIへ渡し、MOD自身では音声デバイスを開かない。 */
public final class MinecraftSoundPlayback implements SpeechService.AudioPlayback {
    private static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final AtomicReference<PlaybackJob> current = new AtomicReference<>();

    /** WAVを復号してゲーム側で再生を開始し、完了・取り消し・開始タイムアウトまで待機する。 */
    @Override
    public void play(byte[] wav, BooleanSupplier cancelled) throws Exception {
        // Decoding and waiting happen only on SpeechService's worker.
        PcmAudio audio = PcmAudio.decodeWav(wav);
        if (cancelled.getAsBoolean()) return;
        PlaybackJob job = new PlaybackJob(audio, cancelled);
        if (!current.compareAndSet(null, job)) throw new IllegalStateException("Speech playback is already active");
        Minecraft client = Minecraft.getInstance();
        try {
            client.execute(() -> start(client, job));
            while (!job.cancelled && !cancelled.getAsBoolean()) {
                try {
                    job.finished.get(100, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException exception) {
                    if (!job.started && System.nanoTime() - job.createdNanos > START_TIMEOUT_NANOS) {
                        throw new IOException("Minecraftの音声再生を開始できませんでした。");
                    }
                } catch (ExecutionException exception) {
                    throw new IOException("Minecraftの音声再生に失敗しました。", exception.getCause());
                }
            }
        } finally {
            job.cancelled = true;
            current.compareAndSet(job, null);
            // Capture this job: a delayed cancellation must never stop the next message.
            client.execute(() -> finish(job, null));
        }
    }

    /** 現在の再生だけを取り消し対象にし、クライアントスレッドへ停止処理を予約する。 */
    @Override
    public void stop() {
        PlaybackJob job = current.get();
        if (job != null) {
            job.cancelled = true;
            Minecraft.getInstance().execute(() -> finish(job, null));
        }
    }

    /** クライアントの各更新で再生終了・取り消し・時間超過を確認し、該当する再生を終了する。 */
    public void tick(Minecraft client) {
        PlaybackJob job = current.get();
        if (job == null || !job.started || job.finished.isDone()) return;
        try {
            if (job.cancelled || job.cancellation.getAsBoolean() || !job.manager.isActive(job.sound)) {
                finish(job, null);
            } else if (!client.isPaused() && --job.ticksRemaining <= 0) {
                finish(job, new IOException("Minecraftの音声再生が時間内に完了しませんでした。"));
            }
        } catch (RuntimeException exception) {
            finish(job, exception);
        }
    }

    /** 有効な再生要求をMinecraftの音声管理へ渡し、開始できなければ待機中の処理を終了する。 */
    private void start(Minecraft client, PlaybackJob job) {
        if (job.cancelled || job.cancellation.getAsBoolean() || current.get() != job) {
            finish(job, null);
            return;
        }
        try {
            job.manager = client.getSoundManager();
            job.sound = new SpeechSound(job.audio);
            var result = job.manager.play(job.sound);
            job.started = true;
            // Muted categories, unavailable devices and missing resources must not stall the queue.
            if (result != SoundEngine.PlayResult.STARTED) finish(job, null);
        } catch (RuntimeException exception) {
            finish(job, exception);
        }
    }

    /** 対象の音声インスタンスを一度だけ停止し、待機側へ正常終了または失敗を通知する。 */
    private void finish(PlaybackJob job, Throwable failure) {
        // Main-thread-only cleanup; stop the exact instance even if another job is now current.
        if (!job.stopped && job.sound != null && job.manager != null) {
            job.stopped = true;
            try {
                job.manager.stop(job.sound);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
            }
        }
        if (failure == null) job.finished.complete(null);
        else job.finished.completeExceptionally(failure);
    }

    private static final class PlaybackJob {
        final PcmAudio audio;
        final BooleanSupplier cancellation;
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final long createdNanos = System.nanoTime();
        volatile boolean cancelled;
        volatile boolean started;
        // The fields below belong exclusively to Minecraft's client thread.
        SoundManager manager;
        SpeechSound sound;
        boolean stopped;
        long ticksRemaining;

        /** 再生データと取り消し判定を保持し、音声長に余裕を加えた待機上限を計算する。 */
        PlaybackJob(PcmAudio audio, BooleanSupplier cancellation) {
            this.audio = audio;
            this.cancellation = cancellation;
            double seconds = audio.samples().length / (double) audio.format().getFrameSize()
                    / audio.format().getSampleRate();
            ticksRemaining = (long) Math.ceil(seconds * 20) + 400;
        }
    }

    private static final class SpeechSound extends AbstractSoundInstance implements FabricSoundInstance {
        private final PcmAudio audio;

        /** 音声カテゴリで、距離減衰や繰り返しのない読み上げ用サウンドを作成する。 */
        SpeechSound(PcmAudio audio) {
            super(Identifier.fromNamespaceAndPath("voicevoxchat", "speech"), SoundSource.VOICE,
                    SoundInstance.createUnseededRandom());
            this.audio = audio;
            volume = 1.0F;
            pitch = 1.0F;
            looping = false;
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
        }

        /** リソースファイルの代わりに、合成済みPCMを供給する音声ストリームを返す。 */
        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean looping) {
            return CompletableFuture.completedFuture(new PcmStream(audio));
        }
    }

    private static final class PcmStream implements AudioStream {
        private final AudioFormat format;
        private byte[] samples;
        private int position;
        private ByteBuffer buffer;

        /** ストリームへ渡す音声形式とサンプル列を保持する。 */
        PcmStream(PcmAudio audio) {
            format = audio.format();
            samples = audio.samples();
        }

        /** サンプルレートやチャンネル数など、供給するPCM音声の形式を返す。 */
        @Override
        public AudioFormat getFormat() { return format; }

        /** 要求量をフレーム境界に合わせてPCMを読み出し、末尾に達していればnullを返す。 */
        @Override
        public ByteBuffer read(int requestedBytes) {
            if (position >= samples.length) return null;
            int frameSize = format.getFrameSize();
            int length = Math.min(samples.length - position, Math.max(frameSize, requestedBytes));
            length -= length % frameSize;
            if (buffer == null || buffer.capacity() < length) buffer = ByteBuffer.allocateDirect(length);
            buffer.clear();
            buffer.put(samples, position, length).flip();
            position += length;
            // Minecraft copies this into its OpenAL buffer before requesting the next chunk.
            // It does not memFree the returned buffer, so GC-managed direct storage is required.
            return buffer;
        }

        /** 保持するサンプル列とバッファを解放し、実際の再生完了判定は更新処理へ任せる。 */
        @Override
        public void close() {
            samples = new byte[0];
            buffer = null;
            // EOF/close can happen before buffered audio drains; tick() decides completion.
        }
    }
}
