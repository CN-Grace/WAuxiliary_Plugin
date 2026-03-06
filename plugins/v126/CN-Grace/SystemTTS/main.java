import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
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

boolean convertWavToMp3(String wavPath, String mp3Path) {
    FileInputStream fis = null;
    MediaCodec codec = null;
    MediaMuxer muxer = null;
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

        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        muxer = new MediaMuxer(mp3Path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int trackIndex = -1;
        boolean muxerStarted = false;

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
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex >= 0) {
                if (!muxerStarted) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    trackIndex = muxer.addTrack(outputFormat);
                    muxer.start();
                    muxerStarted = true;
                }
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer outputBuf = codec.getOutputBuffer(outputIndex);
                        outputBuf.position(bufferInfo.offset);
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, outputBuf, bufferInfo);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        }

        log("SystemTTS: WAV to MP3 conversion completed");
        return true;
    } catch (Exception e) {
        log("SystemTTS: WAV to MP3 conversion failed: " + e.getMessage());
        return false;
    } finally {
        try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception e) {}
        try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception e) {}
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
        String mp3Path = cacheDir + "/tts_" + ts + ".mp3";
        String silkPath = cacheDir + "/tts_" + ts + ".silk";
        String utteranceId = "tts_" + ts;
        File wavFile = new File(wavPath);
        File mp3File = new File(mp3Path);
        log("SystemTTS: wavPath=" + wavPath + ", mp3Path=" + mp3Path + ", silkPath=" + silkPath);

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
                    if (convertWavToMp3(wavPath, mp3Path)) {
                        log("SystemTTS: MP3 file size=" + mp3File.length());
                        mp3ToSilk(mp3Path, silkPath);
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
                    mp3File.delete();
                    new File(silkPath).delete();
                    log("SystemTTS: temporary files cleaned up");
                });
            }

            public void onError(String utteranceId) {
                log("SystemTTS: synthesis error, utteranceId=" + utteranceId);
                wavFile.delete();
                mp3File.delete();
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
