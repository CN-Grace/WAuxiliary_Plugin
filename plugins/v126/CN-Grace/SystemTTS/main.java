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
    tts = new TextToSpeech(hostContext, new TextToSpeech.OnInitListener() {
        public void onInit(int status) {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINA);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                ttsReady = true;
                log("SystemTTS initialized");
            } else {
                log("SystemTTS init failed");
            }
        }
    });
}

void onUnLoad() {
    if (tts != null) {
        tts.stop();
        tts.shutdown();
    }
}

boolean onClickSendBtn(String text) {
    if (text.startsWith("#tts ")) {
        if (!ttsReady) {
            toast("TTS引擎未就绪");
            return true;
        }
        String content = text.substring(5).trim();
        if (content.isEmpty()) {
            return false;
        }
        String talker = getTargetTalker();
        String wavPath = cacheDir + "/tts_" + System.currentTimeMillis() + ".wav";
        File wavFile = new File(wavPath);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String utteranceId) {}

            public void onDone(String utteranceId) {
                String silkPath = mp3ToSilkPath(wavPath);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (silkPath != null && new File(silkPath).exists()) {
                        sendVoice(talker, silkPath);
                        new File(silkPath).delete();
                    } else {
                        log("TTS silk conversion failed");
                        toast("语音转换失败");
                    }
                    wavFile.delete();
                });
            }

            public void onError(String utteranceId) {
                log("TTS synthesis failed");
                wavFile.delete();
            }
        });

        tts.synthesizeToFile(content, new Bundle(), wavFile, "tts_" + System.currentTimeMillis());
        return true;
    }
    return false;
}
