/* VOICEVOXのWAV応答を検証し、Minecraftのサウンド機能に渡せるPCM形式と音声データへ変換する。 */
package jp.voicevoxchat.speech;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** WAVのデコード結果を保持する。実際の再生はMinecraftのサウンド機能が担当する。 */
public record PcmAudio(AudioFormat format, byte[] samples) {
    /** 16bitリトルエンディアンPCMの形式・サンプルレート・データ長を検証し、音声データを返す。 */
    public static PcmAudio decodeWav(byte[] wav) throws IOException {
        try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat format = stream.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                    || (format.getChannels() != 1 && format.getChannels() != 2)
                    || format.getFrameSize() != format.getChannels() * 2
                    || !Float.isFinite(format.getSampleRate())
                    || format.getSampleRate() <= 0 || format.getSampleRate() > 192000) {
                throw new IOException("VOICEVOXの音声は16bit PCM WAV（モノラルまたはステレオ）である必要があります。");
            }
            byte[] samples = stream.readAllBytes();
            if (samples.length == 0 || samples.length % format.getFrameSize() != 0
                    || (stream.getFrameLength() >= 0
                    && stream.getFrameLength() * format.getFrameSize() != samples.length)) {
                throw new IOException("VOICEVOXのWAV音声が空、または途中で切れています。");
            }
            return new PcmAudio(format, samples);
        } catch (UnsupportedAudioFileException exception) {
            throw new IOException("VOICEVOXの応答をWAV音声として読み込めません。", exception);
        }
    }
}
