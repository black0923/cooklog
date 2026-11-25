package com.cooklog.kitchen;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LanSharePlugin.class);
        registerPlugin(BackupPlugin.class);
        registerPlugin(AiPlugin.class);
        registerPlugin(TtsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
