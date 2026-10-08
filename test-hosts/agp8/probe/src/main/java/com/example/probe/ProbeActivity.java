package com.example.probe;

import android.app.Activity;
import android.os.Bundle;

/** 只为让应用能被启动，自检逻辑全部放在 ContentProvider 里。 */
public class ProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
