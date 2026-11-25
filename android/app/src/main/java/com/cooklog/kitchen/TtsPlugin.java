package com.cooklog.kitchen;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Locale;
import java.util.UUID;

/** 用 Android 原生 TextToSpeech 逐句朗读做法步骤，不依赖 WebView 的 speechSynthesis。 */
@CapacitorPlugin(name = "Tts")
public class TtsPlugin extends Plugin {

    private TextToSpeech tts;
    private boolean initing = false;
    private PluginCall current;
    private String currentId;

    private interface Ready { void ok(boolean yes); }

    private void ensure(PluginCall call, final Ready cb) {
        if (tts != null) { cb.ok(true); return; }
        if (initing) { call.reject("语音引擎正在初始化，请稍后再试"); return; }
        initing = true;
        try {
            tts = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    initing = false;
                    if (status != TextToSpeech.SUCCESS) { cb.ok(false); return; }
                    int r = tts.setLanguage(Locale.CHINA);
                    cb.ok(r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
                }
            });
            tts.setOnUtteranceProgressListener(progress);
        } catch (Exception e) {
            initing = false;
            cb.ok(false);
        }
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String text = call.getString("text", "");
        if (text == null || text.trim().isEmpty()) { call.reject("没有可朗读的内容"); return; }
        float rate = 1f;
        try {
            Double rv = call.getDouble("rate");
            if (rv != null) rate = rv.floatValue();
        } catch (Exception ignored) { }
        if (rate <= 0) rate = 1f;
        final float finalRate = rate;
        float pitch = 1f;
        try {
            Double pv = call.getDouble("pitch");
            if (pv != null) pitch = pv.floatValue();
        } catch (Exception ignored) { }
        final float finalPitch = pitch;
        ensure(call, new Ready() {
            @Override public void ok(boolean yes) {
                if (!yes) { call.reject("系统缺少中文语音包"); return; }
                if (current != null) { PluginCall c = current; current = null; currentId = null; c.reject("interrupted"); }
                String id = UUID.randomUUID().toString();
                currentId = id;
                current = call;
                try {
                    tts.setSpeechRate(finalRate);
                    tts.setPitch(finalPitch);
                    int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                    if (res != TextToSpeech.SUCCESS) { current = null; currentId = null; call.reject("朗读失败"); }
                } catch (Exception e) { current = null; currentId = null; call.reject("朗读失败"); }
            }
        });
    }

    private final UtteranceProgressListener progress = new UtteranceProgressListener() {
        @Override public void onStart(String utteranceId) { }
        @Override public void onDone(String utteranceId) { finish(utteranceId, false); }
        @Override public void onError(String utteranceId) { finish(utteranceId, true); }
    };

    private void finish(String id, boolean failed) {
        if (current == null || currentId == null || !currentId.equals(id)) return;
        PluginCall c = current;
        current = null;
        currentId = null;
        if (failed) {
            c.reject("朗读失败");
        } else {
            JSObject ret = new JSObject();
            ret.put("ok", true);
            c.resolve(ret);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (current != null) {
            PluginCall c = current;
            current = null;
            currentId = null;
            c.reject("interrupted");
        }
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        } catch (Exception ignored) { }
        super.handleOnDestroy();
    }
}
