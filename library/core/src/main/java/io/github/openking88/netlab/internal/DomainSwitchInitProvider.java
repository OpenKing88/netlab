package io.github.openking88.netlab.internal;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import io.github.openking88.netlab.DomainSwitch;
import io.github.openking88.netlab.capture.CaptureStore;

/**
 * 零代码初始化入口：ContentProvider 的 onCreate 早于 Application.onCreate 中的业务代码，
 * 因此宿主不必写任何 init 调用。
 */
public final class DomainSwitchInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        DomainSwitch.initialize(getContext());
        CaptureStore.initialize(getContext());
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
