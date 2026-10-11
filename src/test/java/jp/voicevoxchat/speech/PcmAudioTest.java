/**
 * VOICEVOXのWAV音声をPCMとして読み込み、サンプルの保持と不正形式の拒否を検証する。
 */
package jp.voicevoxchat.speech;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PcmAudioTest {
    /** モノラル16ビット音声の形式とサンプル値を変更せずに読み込むことを検証する。 */
    @Test
    void decodesVoicevoxMonoWithoutChangingSamples() throws Exception {
        byte[] pcm = {0, 0, -1, 127, 0, -128, 10, 0};
        var audio = PcmAudio.decodeWav(wav(1, 16, pcm));
        assertEquals(24000f, audio.format().getSampleRate());
        assertEquals(1, audio.format().getChannels());
        assertEquals(16, audio.format().getSampleSizeInBits());
        assertFalse(audio.format().isBigEndian());
        assertArrayEquals(pcm, audio.samples());
    }

    /** ステレオの左右交互に並ぶサンプルとフレームサイズが保持されることを検証する。 */
    @Test
    void preservesStereoInterleaving() throws Exception {
        byte[] pcm = {1, 0, 2, 0, 3, 0, 4, 0};
        var audio = PcmAudio.decodeWav(wav(2, 16, pcm));
        assertEquals(2, audio.format().getChannels());
        assertEquals(4, audio.format().getFrameSize());
        assertArrayEquals(pcm, audio.samples());
    }

    /** WAV以外のデータ、空音声、途中で欠けた音声を拒否することを検証する。 */
    @Test
    void rejectsMalformedEmptyAndTruncatedResponses() {
        assertThrows(IOException.class, () -> PcmAudio.decodeWav("not a wav".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> PcmAudio.decodeWav(wav(1, 16, new byte[0])));
        byte[] complete = wav(1, 16, new byte[8]);
        assertThrows(IOException.class, () -> PcmAudio.decodeWav(Arrays.copyOf(complete, complete.length - 2)));
    }

    /** 未対応の8ビットおよび24ビット音声を拒否することを検証する。 */
    @Test
    void rejectsUnsupportedSampleFormat() {
        assertThrows(IOException.class, () -> PcmAudio.decodeWav(wav(1, 8, new byte[8])));
        assertThrows(IOException.class, () -> PcmAudio.decodeWav(wav(1, 24, new byte[12])));
    }

    /** 指定チャンネル数・ビット深度・サンプル列から24kHzのテスト用WAVデータを生成する。 */
    private static byte[] wav(int channels, int bits, byte[] pcm) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        buffer.putShort((short) 1).putShort((short) channels).putInt(24000);
        buffer.putInt(24000 * channels * bits / 8).putShort((short) (channels * bits / 8));
        buffer.putShort((short) bits).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        return buffer.put(pcm).array();
    }
}
