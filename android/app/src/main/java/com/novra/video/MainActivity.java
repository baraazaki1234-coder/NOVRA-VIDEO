package com.novra.video;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (getBridge() != null) {
            getBridge().triggerJSEvent("novraShareIntent", "window", "{}");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NovraMediaPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
