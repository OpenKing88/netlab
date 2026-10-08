package io.github.openking88.netlab.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import io.github.openking88.netlab.internal.ResourceConfig;

/**
 * 抓包记录仓库。
 *
 * <p>与 Monitor 的实现相比，这里改掉了两个结构性问题：
 * <ol>
 *   <li><b>不再有全局写锁</b>。Monitor 用一个 Mutex 把"插入"和"更新"全部串起来，
 *       高并发下互相排队；这里用一个单线程 Executor 天然串行，DB 操作也彻底离开 OkHttp 线程。</li>
 *   <li><b>不再每个请求写两次库</b>。Monitor 先插 pending 再 update；这里内存里的记录是
 *       实时可读的（界面照样能看到"请求中"），落库只在完成时做一次。</li>
 * </ol>
 */
public final class CaptureStore {

    /** 记录变化通知，回调在主线程。 */
    public interface Listener {
        void onCaptureChanged();
    }

    private static final int DEFAULT_MAX_RECORDS = 500;

    private static volatile CaptureStore instance;

    public static void initialize(Context context) {
        if (context == null) {
            return;
        }
        if (instance == null) {
            synchronized (CaptureStore.class) {
                if (instance == null) {
                    Context application = context.getApplicationContext();
                    instance = new CaptureStore(
                            application,
                            ResourceConfig.getInt(application, "domain_switch_max_records", DEFAULT_MAX_RECORDS),
                            ResourceConfig.getInt(
                                    application,
                                    "domain_switch_max_body_bytes",
                                    (int) CaptureBody.DEFAULT_MAX_BODY_BYTES
                            ),
                            ResourceConfig.getBoolean(application, "domain_switch_capture_enabled", true)
                    );
                }
            }
        }
    }

    /** 还没初始化时返回 null，调用方按"没开启抓包"处理。 */
    public static CaptureStore peek() {
        return instance;
    }

    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "domain-switch-capture");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<CaptureRecord> memory = new CopyOnWriteArrayList<>();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final CaptureDatabase database;

    private final int maxRecords;

    private final long maxBodyBytes;

    private final AtomicLong ids = new AtomicLong();

    private volatile boolean enabled = true;

    private CaptureStore(Context context, int maxRecords, long maxBodyBytes, boolean enabled) {
        this.database = new CaptureDatabase(context);
        this.maxRecords = maxRecords;
        this.maxBodyBytes = maxBodyBytes;
        this.enabled = enabled;

        List<CaptureRecord> history = database.queryRecent(maxRecords);
        memory.addAll(history);
        if (!history.isEmpty()) {
            ids.set(history.get(0).id);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        notifyListeners();
    }

    public long maxBodyBytes() {
        return maxBodyBytes;
    }

    public int maxRecords() {
        return maxRecords;
    }

    public long nextId() {
        return ids.incrementAndGet();
    }

    /** 最新的在最前面。 */
    public List<CaptureRecord> records() {
        return Collections.unmodifiableList(new ArrayList<>(memory));
    }

    /** 请求发出时先放进内存，界面立刻能看到"请求中"。 */
    public void add(CaptureRecord record) {
        if (record == null) {
            return;
        }
        memory.add(0, record);
        trim();
        notifyListeners();
    }

    /** 请求结束后补齐并落库（落库在专用线程）。 */
    public void complete(CaptureRecord record, CaptureSnapshot snapshot) {
        if (record == null) {
            return;
        }
        writer.execute(() -> {
            decode(record, snapshot);
            database.insert(record);
            database.prune(maxRecords);
            notifyListeners();
        });
    }

    public void clear() {
        memory.clear();
        notifyListeners();
        writer.execute(() -> {
            database.deleteAll();
            notifyListeners();
        });
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private void decode(CaptureRecord record, CaptureSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            record.requestBodyState = snapshot.requestBody.state;
            record.requestBody = CaptureBody.decode(snapshot.requestBody, snapshot.requestCharset);
            record.responseBodyState = snapshot.responseBody.state;
            record.responseBody = CaptureBody.decode(snapshot.responseBody, snapshot.responseCharset);
        } catch (Throwable ignored) {
            // 解码失败不能影响记录本身
        }
    }

    private void trim() {
        while (memory.size() > maxRecords) {
            memory.remove(memory.size() - 1);
        }
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) {
            return;
        }
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onCaptureChanged();
                } catch (Throwable ignored) {
                    // 单个监听者出错不影响其它监听者
                }
            }
        });
    }
}
