import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

TextToSpeech tts;
boolean ttsReady = false;
final long MAX_SILK_SIZE = 5 * 1024 * 1024;

void onLoad() {
    log("SystemTTS onLoad: initializing TTS engine...");
    tts = new TextToSpeech(hostContext, new TextToSpeech.OnInitListener() {
        public void onInit(int status) {
            log("SystemTTS onInit: status=" + status);
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINA);
                log("SystemTTS setLanguage(CHINA) result=" + result);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Locale fallback = Locale.getDefault();
                    int fallbackResult = tts.setLanguage(fallback);
                    log("SystemTTS fallback to " + fallback + " result=" + fallbackResult);
                }
                ttsReady = true;
                log("SystemTTS initialized successfully, ttsReady=true");
            } else {
                log("SystemTTS init failed with status=" + status);
            }
        }
    });
}

void onUnLoad() {
    log("SystemTTS onUnLoad: shutting down TTS engine");
    if (tts != null) {
        tts.stop();
        tts.shutdown();
    }
}

int getFreqIndex(int sampleRate) {
    int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    for (int i = 0; i < rates.length; i++) {
        if (rates[i] == sampleRate) return i;
    }
    return 8; // default: 16000 Hz
}

byte[] createAdtsHeader(int profile, int freqIndex, int channels, int aacFrameLength) {
    int frameLength = aacFrameLength + 7;
    byte[] header = new byte[7];
    header[0] = (byte) 0xFF;
    header[1] = (byte) 0xF1;
    header[2] = (byte) (((profile - 1) << 6) | (freqIndex << 2) | (channels >> 2));
    header[3] = (byte) (((channels & 3) << 6) | (frameLength >> 11));
    header[4] = (byte) ((frameLength >> 3) & 0xFF);
    header[5] = (byte) (((frameLength & 7) << 5) | 0x1F);
    header[6] = (byte) 0xFC;
    return header;
}

boolean convertWavToAac(String wavPath, String aacPath) {
    FileInputStream fis = null;
    FileOutputStream fos = null;
    MediaCodec codec = null;
    try {
        fis = new FileInputStream(wavPath);
        byte[] riffHeader = new byte[12];
        if (fis.read(riffHeader) != 12) {
            log("SystemTTS: WAV file too small");
            return false;
        }

        int channels = 1;
        int sampleRate = 16000;
        int bitsPerSample = 16;

        while (true) {
            byte[] chunkHeader = new byte[8];
            int n = fis.read(chunkHeader);
            if (n < 8) break;
            String chunkId = new String(chunkHeader, 0, 4);
            int chunkSize = (chunkHeader[4] & 0xFF) | ((chunkHeader[5] & 0xFF) << 8) |
                            ((chunkHeader[6] & 0xFF) << 16) | ((chunkHeader[7] & 0xFF) << 24);
            if ("fmt ".equals(chunkId)) {
                byte[] fmtData = new byte[chunkSize];
                if (fis.read(fmtData) != chunkSize) break;
                channels = (fmtData[2] & 0xFF) | ((fmtData[3] & 0xFF) << 8);
                sampleRate = (fmtData[4] & 0xFF) | ((fmtData[5] & 0xFF) << 8) |
                             ((fmtData[6] & 0xFF) << 16) | ((fmtData[7] & 0xFF) << 24);
                bitsPerSample = (fmtData[14] & 0xFF) | ((fmtData[15] & 0xFF) << 8);
            } else if ("data".equals(chunkId)) {
                break;
            } else {
                fis.skip(chunkSize);
            }
        }

        log("SystemTTS: WAV format - channels=" + channels + ", sampleRate=" + sampleRate + ", bitsPerSample=" + bitsPerSample);

        int freqIndex = getFreqIndex(sampleRate);
        int profile = 2; // AAC-LC

        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        fos = new FileOutputStream(aacPath);

        byte[] readBuffer = new byte[8192];
        boolean inputDone = false;
        boolean outputDone = false;
        long totalBytesRead = 0;
        int bytesPerFrame = channels * (bitsPerSample / 8);

        while (!outputDone) {
            if (!inputDone) {
                int inputIndex = codec.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuf = codec.getInputBuffer(inputIndex);
                    int toRead = Math.min(readBuffer.length, inputBuf.remaining());
                    int read = fis.read(readBuffer, 0, toRead);
                    if (read <= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        inputBuf.put(readBuffer, 0, read);
                        totalBytesRead += read;
                        long pts = (totalBytesRead * 1000000L) / (long)(sampleRate * bytesPerFrame);
                        codec.queueInputBuffer(inputIndex, 0, read, pts, 0);
                    }
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputIndex >= 0) {
                if (bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer outputBuf = codec.getOutputBuffer(outputIndex);
                    outputBuf.position(bufferInfo.offset);
                    byte[] aacData = new byte[bufferInfo.size];
                    outputBuf.get(aacData);
                    byte[] adtsHeader = createAdtsHeader(profile, freqIndex, channels, aacData.length);
                    fos.write(adtsHeader);
                    fos.write(aacData);
                }
                codec.releaseOutputBuffer(outputIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        log("SystemTTS: WAV to AAC conversion completed");
        return true;
    } catch (Exception e) {
        log("SystemTTS: WAV to AAC conversion failed: " + e.getMessage());
        return false;
    } finally {
        try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception e) {}
        try { if (fos != null) fos.close(); } catch (Exception e) {}
        try { if (fis != null) fis.close(); } catch (Exception e) {}
    }
}

boolean onClickSendBtn(String text) {
    if (text.startsWith("#tts ")) {
        log("SystemTTS command received: " + text);
        if (!ttsReady) {
            log("SystemTTS: engine not ready, aborting");
            toast("TTS引擎未就绪");
            return true;
        }
        String content = text.substring(5).trim();
        log("SystemTTS: content to synthesize: " + content);
        if (content.isEmpty()) {
            log("SystemTTS: content is empty, ignoring");
            return false;
        }
        String talker = getTargetTalker();
        log("SystemTTS: target talker=" + talker);
        long ts = System.currentTimeMillis();
        String wavPath = cacheDir + "/tts_" + ts + ".wav";
        String aacPath = cacheDir + "/tts_" + ts + ".aac";
        String silkPath = cacheDir + "/tts_" + ts + ".silk";
        String utteranceId = "tts_" + ts;
        File wavFile = new File(wavPath);
        File aacFile = new File(aacPath);
        log("SystemTTS: wavPath=" + wavPath + ", aacPath=" + aacPath + ", silkPath=" + silkPath);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String utteranceId) {
                log("SystemTTS: synthesis started, utteranceId=" + utteranceId);
            }

            public void onDone(String utteranceId) {
                log("SystemTTS: synthesis done, utteranceId=" + utteranceId);
                log("SystemTTS: wav file exists=" + wavFile.exists() + ", size=" + wavFile.length());

                boolean conversionOk = false;
                File silkFile = new File(silkPath);

                try {
                    if (convertWavToAac(wavPath, aacPath)) {
                        log("SystemTTS: AAC file size=" + aacFile.length());
                        mp3ToSilk(aacPath, silkPath);
                        log("SystemTTS: silk file size=" + silkFile.length());
                        if (silkFile.exists() && silkFile.length() > 0 && silkFile.length() < MAX_SILK_SIZE) {
                            conversionOk = true;
                        } else {
                            log("SystemTTS: silk file invalid - size=" + (silkFile.exists() ? silkFile.length() : 0));
                        }
                    }
                } catch (Exception e) {
                    log("SystemTTS: conversion error: " + e.getMessage());
                }

                boolean ok = conversionOk;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (ok) {
                        log("SystemTTS: sending voice to " + talker);
                        sendVoice(talker, silkPath);
                        log("SystemTTS: voice sent successfully");
                    } else {
                        log("SystemTTS: conversion failed");
                        toast("语音转换失败");
                    }
                    wavFile.delete();
                    aacFile.delete();
                    new File(silkPath).delete();
                    log("SystemTTS: temporary files cleaned up");
                });
            }

            public void onError(String utteranceId) {
                log("SystemTTS: synthesis error, utteranceId=" + utteranceId);
                wavFile.delete();
                aacFile.delete();
                toast("语音合成失败");
            }
        });

        log("SystemTTS: calling synthesizeToFile, utteranceId=" + utteranceId);
        int synthResult = tts.synthesizeToFile(content, new Bundle(), wavFile, utteranceId);
        log("SystemTTS: synthesizeToFile result=" + synthResult);
        toast("语音合成中...");
        return true;
    }
    return false;
}
