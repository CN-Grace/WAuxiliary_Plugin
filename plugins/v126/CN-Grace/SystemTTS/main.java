import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.Locale;

TextToSpeech tts;
boolean ttsReady = false;

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
        String silkPath = cacheDir + "/tts_" + ts + ".silk";
        String utteranceId = "tts_" + ts;
        File wavFile = new File(wavPath);
        log("SystemTTS: wavPath=" + wavPath + ", silkPath=" + silkPath + ", utteranceId=" + utteranceId);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String utteranceId) {
                log("SystemTTS: synthesis started, utteranceId=" + utteranceId);
            }

            public void onDone(String utteranceId) {
                log("SystemTTS: synthesis done, utteranceId=" + utteranceId);
                log("SystemTTS: wav file exists=" + wavFile.exists() + ", size=" + wavFile.length());
                try {
                    mp3ToSilk(wavPath, silkPath);
                    log("SystemTTS: mp3ToSilk conversion completed");
                } catch (Exception e) {
                    log("SystemTTS: mp3ToSilk exception: " + e.getMessage());
                }
                File silkFile = new File(silkPath);
                log("SystemTTS: silk file exists=" + silkFile.exists() + ", size=" + silkFile.length());
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (silkFile.exists() && silkFile.length() > 0) {
                        log("SystemTTS: sending voice to " + talker);
                        sendVoice(talker, silkPath);
                        log("SystemTTS: voice sent successfully");
                        silkFile.delete();
                    } else {
                        log("SystemTTS: silk conversion failed, silk file missing or empty");
                        toast("语音转换失败");
                    }
                    wavFile.delete();
                    log("SystemTTS: temporary files cleaned up");
                });
            }

            public void onError(String utteranceId) {
                log("SystemTTS: synthesis error, utteranceId=" + utteranceId);
                wavFile.delete();
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
