package com.novra.video;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null &&
            (android.content.Intent.ACTION_SEND.equals(intent.getAction()) ||
             android.content.Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) &&
            getBridge() != null) {
            getBridge().triggerJSEvent("novraShareIntent", "window", "{}");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NovraMediaPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
