package com.matrix.editor;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.matrix.editor.plugins.SshBridgePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SshBridgePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
