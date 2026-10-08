package com.example.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import io.github.openking88.netlab.DomainSwitch;
import okhttp3.Request;

/** 运行时自检：打印实际生效的拦截器顺序，并走一次真实请求链路验证改写。 */
public class ProbeSelfCheckProvider extends ContentProvider {

    private static final String TAG = "DomainSwitchAgp8";

    @Override
    public boolean onCreate() {
        try {
            Log.i(TAG, "拦截器顺序 = " + ProbeNetwork.CLIENT.interceptors());

            Log.i(TAG, "自动识别的配置域名(库初始化前) = " + DomainSwitch.configuredHosts());

            DomainSwitch.setTarget("api.example.com", "api.pre-test.internal");
            Log.i(TAG, "生效的映射 = " + DomainSwitch.targets());

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(300);
                        Log.i(TAG, "自动识别的配置域名(库初始化后) = " + DomainSwitch.configuredHosts());
                        DomainSwitch.addCustomTarget("https://custom-target.example.org/live");
                        Log.i(TAG, "自动识别 + 自定义的候选列表 = " + DomainSwitch.targetOptions());
                        ProbeNetwork.CLIENT.newCall(
                                new Request.Builder()
                                        .url("https://api.example.com/v1/user")
                                        .build()
                        ).execute().close();
                        Log.i(TAG, "真实请求链路: 请求成功了（不应该发生）");
                    } catch (Throwable error) {
                        Log.i(TAG, "真实请求链路: " + error.getMessage());
                    } finally {
                        DomainSwitch.clear();
                    }
                }
            }, "domain-switch-selfcheck").start();
        } catch (Throwable error) {
            Log.e(TAG, "自检失败", error);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
