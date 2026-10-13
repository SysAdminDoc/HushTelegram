/*
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushtelegram.extension.shared.settings;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushtelegram.extension.shared.settings.preference.SharedPrefCategory;

/**
 * Setting's store with a fault put in on purpose for each editor it opens, in order. Editors past
 * the list work. Closing it puts the store it replaced back. {@link #holding} also keeps the first
 * commit waiting, for what happens while a write is still out.
 */
public final class FailingStore implements AutoCloseable {
    /** What one editor does. */
    public enum Fault {
        /** Works. */
        NONE,
        /** {@code edit()} throws. */
        EDIT_THROWS,
        /** The editor refuses the first value it's handed. */
        STAGE_THROWS,
        /** The commit lands and still answers false. */
        COMMIT_FALSE,
        /** The commit throws before it lands. */
        COMMIT_THROWS,
        /** The commit lands, then throws. */
        COMMIT_THROWS_AFTER_LANDING,
        /** The commit never lands and answers false. */
        LOST
    }

    /** How many editors were asked for, the refused ones included. */
    public final AtomicInteger editors = new AtomicInteger();

    /** Whether the next commit waits at the store, as a slow disk makes it. */
    private final AtomicBoolean holds = new AtomicBoolean();
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    /** The thread the held commit waits on. */
    private volatile Thread holder;

    private final Fault[] faults;
    private final Field field;
    private final Object replaced;

    private FailingStore(Fault[] faults, boolean holdFirstCommit) throws ReflectiveOperationException {
        this.faults = faults;
        holds.set(holdFirstCommit);
        field = SharedPrefCategory.class.getDeclaredField("preferences");
        field.setAccessible(true);
        replaced = field.get(Setting.preferences);
        field.set(Setting.preferences, faulty((SharedPreferences) replaced));
    }

    /** [target] with the faults and the hold in front of it. */
    private SharedPreferences faulty(SharedPreferences target) {
        return (SharedPreferences) Proxy.newProxyInstance(target.getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return call(method, target, args);
                    int index = editors.getAndIncrement();
                    Fault fault = index < faults.length ? faults[index] : Fault.NONE;
                    if (fault == Fault.EDIT_THROWS) throw new IllegalStateException("injected editor failure");
                    SharedPreferences.Editor editor = target.edit();
                    return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, edit, values) -> {
                                String name = edit.getName();
                                if (name.equals("commit")) {
                                    if (holds.compareAndSet(true, false)) {
                                        holder = Thread.currentThread();
                                        reached.countDown();
                                        if (!released.await(10, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException("the held commit was never let go");
                                        }
                                    }
                                    return commit(fault, editor);
                                }
                                if (fault == Fault.STAGE_THROWS && (name.startsWith("put") || name.equals("remove"))) {
                                    throw new IllegalArgumentException("injected staging failure");
                                }
                                Object result = call(edit, editor, values);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }

    /** Puts the faults in, one per editor, until {@link #close()}. */
    public static FailingStore install(Fault... faults) {
        try {
            return new FailingStore(faults, false);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not replace Setting's store", failure);
        }
    }

    /**
     * The same, with the first commit waiting at the store until {@link #letGo()}: a write caught
     * part way, with its caller still inside it. Whatever fault that editor has comes after the wait.
     */
    public static FailingStore holding(Fault... faults) {
        try {
            return new FailingStore(faults, true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not replace Setting's store", failure);
        }
    }

    /** Waits for the held commit to reach the store, and answers whether it did. */
    public boolean awaitHeld() throws InterruptedException {
        return reached.await(5, TimeUnit.SECONDS);
    }

    /** The thread the held commit is waiting on, or null when none has reached the store. */
    public Thread heldOn() {
        return holder;
    }

    /**
     * [context] with its preference file [name] behind the same faults and hold, for a store that
     * isn't Setting's. Its editors count along with Setting's.
     */
    public Context over(Context context, String name) {
        return new ContextWrapper(context) {
            @Override
            public SharedPreferences getSharedPreferences(String file, int mode) {
                SharedPreferences store = super.getSharedPreferences(file, mode);
                return name.equals(file) ? faulty(store) : store;
            }
        };
    }

    /** Lets the held commit through. */
    public void letGo() {
        released.countDown();
    }

    @Override
    public void close() {
        // A test that failed while the commit was held leaves no worker waiting on it.
        released.countDown();
        try {
            field.set(Setting.preferences, replaced);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Could not put Setting's store back", failure);
        }
    }

    private static boolean commit(Fault fault, SharedPreferences.Editor editor) {
        switch (fault) {
            case COMMIT_THROWS:
                throw new IllegalStateException("injected commit failure");
            case LOST:
                return false;
            case COMMIT_FALSE:
                editor.commit();
                return false;
            case COMMIT_THROWS_AFTER_LANDING:
                editor.commit();
                throw new IllegalStateException("injected commit failure after landing");
            default:
                return editor.commit();
        }
    }

    private static Object call(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
