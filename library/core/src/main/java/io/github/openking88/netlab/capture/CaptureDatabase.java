package io.github.openking88.netlab.capture;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 抓包记录的持久化。
 *
 * <p>刻意不用 Room：那会把 Room + KSP 注解处理器塞进宿主的构建流程，还有版本冲突风险。
 * 这里就是一张表，用系统自带的 SQLiteOpenHelper，零额外依赖、零注解处理。
 */
final class CaptureDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "domain_switch_capture";
    /** v2：加上 Retrofit 接口信息（service / method / 说明）。 */
    private static final int DATABASE_VERSION = 2;

    private static final String TABLE = "capture_record";

    CaptureDatabase(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE " + TABLE + " ("
                        + "id INTEGER PRIMARY KEY,"
                        + "state TEXT,"
                        + "method TEXT,"
                        + "url TEXT,"
                        + "host TEXT,"
                        + "pathWithQuery TEXT,"
                        + "originalUrl TEXT,"
                        + "retrofitService TEXT,"
                        + "retrofitMethod TEXT,"
                        + "apiDescription TEXT,"
                        + "startedAt INTEGER,"
                        + "finishedAt INTEGER,"
                        + "requestHeaders TEXT,"
                        + "requestContentType TEXT,"
                        + "requestContentLength INTEGER,"
                        + "requestBodyState TEXT,"
                        + "requestBody TEXT,"
                        + "responseCode INTEGER,"
                        + "responseMessage TEXT,"
                        + "responseHeaders TEXT,"
                        + "responseContentType TEXT,"
                        + "responseContentLength INTEGER,"
                        + "responseBodyState TEXT,"
                        + "responseBody TEXT,"
                        + "protocol TEXT,"
                        + "tlsVersion TEXT,"
                        + "cipherSuite TEXT,"
                        + "error TEXT"
                        + ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        // 抓包数据是调试产物，结构变更时重建即可，不做数据迁移
        database.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(database);
    }

    void insert(CaptureRecord record) {
        try {
            getWritableDatabase().insertWithOnConflict(
                    TABLE,
                    null,
                    toValues(record),
                    SQLiteDatabase.CONFLICT_REPLACE
            );
        } catch (Throwable ignored) {
            // 落库失败不能影响业务
        }
    }

    List<CaptureRecord> queryRecent(int limit) {
        List<CaptureRecord> records = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(
                    TABLE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "id DESC",
                    String.valueOf(limit)
            );
            while (cursor.moveToNext()) {
                records.add(fromCursor(cursor));
            }
        } catch (Throwable ignored) {
            // 读不出来就当没有历史
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return records;
    }

    /** 只保留最新的 {@code maxRecords} 条。 */
    void prune(int maxRecords) {
        try {
            getWritableDatabase().execSQL(
                    "DELETE FROM " + TABLE + " WHERE id NOT IN "
                            + "(SELECT id FROM " + TABLE + " ORDER BY id DESC LIMIT " + maxRecords + ")"
            );
        } catch (Throwable ignored) {
            // 同上
        }
    }

    void deleteAll() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    private static ContentValues toValues(CaptureRecord record) {
        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("state", record.state);
        values.put("method", record.method);
        values.put("url", record.url);
        values.put("host", record.host);
        values.put("pathWithQuery", record.pathWithQuery);
        values.put("originalUrl", record.originalUrl);
        values.put("retrofitService", record.retrofitService);
        values.put("retrofitMethod", record.retrofitMethod);
        values.put("apiDescription", record.apiDescription);
        values.put("startedAt", record.startedAtMillis);
        values.put("finishedAt", record.finishedAtMillis);
        values.put("requestHeaders", record.requestHeaders);
        values.put("requestContentType", record.requestContentType);
        values.put("requestContentLength", record.requestContentLength);
        values.put("requestBodyState", record.requestBodyState);
        values.put("requestBody", record.requestBody);
        values.put("responseCode", record.responseCode);
        values.put("responseMessage", record.responseMessage);
        values.put("responseHeaders", record.responseHeaders);
        values.put("responseContentType", record.responseContentType);
        values.put("responseContentLength", record.responseContentLength);
        values.put("responseBodyState", record.responseBodyState);
        values.put("responseBody", record.responseBody);
        values.put("protocol", record.protocol);
        values.put("tlsVersion", record.tlsVersion);
        values.put("cipherSuite", record.cipherSuite);
        values.put("error", record.error);
        return values;
    }

    private static CaptureRecord fromCursor(Cursor cursor) {
        CaptureRecord record = new CaptureRecord();
        record.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        record.state = string(cursor, "state");
        record.method = string(cursor, "method");
        record.url = string(cursor, "url");
        record.host = string(cursor, "host");
        record.pathWithQuery = string(cursor, "pathWithQuery");
        record.originalUrl = cursor.isNull(cursor.getColumnIndexOrThrow("originalUrl"))
                ? null
                : string(cursor, "originalUrl");
        record.retrofitService = nullableString(cursor, "retrofitService");
        record.retrofitMethod = nullableString(cursor, "retrofitMethod");
        record.apiDescription = nullableString(cursor, "apiDescription");
        record.startedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("startedAt"));
        record.finishedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("finishedAt"));
        record.requestHeaders = string(cursor, "requestHeaders");
        record.requestContentType = string(cursor, "requestContentType");
        record.requestContentLength = cursor.getLong(cursor.getColumnIndexOrThrow("requestContentLength"));
        record.requestBodyState = string(cursor, "requestBodyState");
        record.requestBody = string(cursor, "requestBody");
        record.responseCode = cursor.getInt(cursor.getColumnIndexOrThrow("responseCode"));
        record.responseMessage = string(cursor, "responseMessage");
        record.responseHeaders = string(cursor, "responseHeaders");
        record.responseContentType = string(cursor, "responseContentType");
        record.responseContentLength = cursor.getLong(cursor.getColumnIndexOrThrow("responseContentLength"));
        record.responseBodyState = string(cursor, "responseBodyState");
        record.responseBody = string(cursor, "responseBody");
        record.protocol = string(cursor, "protocol");
        record.tlsVersion = string(cursor, "tlsVersion");
        record.cipherSuite = string(cursor, "cipherSuite");
        record.error = cursor.isNull(cursor.getColumnIndexOrThrow("error")) ? null : string(cursor, "error");
        return record;
    }

    private static String string(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static String nullableString(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getString(index);
    }
}
