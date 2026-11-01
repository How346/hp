package app.noko.print;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single source of truth for the printer link.
 *
 * Why the old screen flickered between "connected" and "not connected":
 * connect taps, permission callbacks, attach/detach events and failed writes
 * all wrote straight to the label, and every "looking for printer" step
 * briefly reported "not connected" even when a good link was open.
 *
 * This class instead: runs one state machine on the main thread, never
 * polls, ignores duplicate events, only leaves CONNECTED on a real detach or
 * a failed transfer, and only notifies the UI when something actually changed.
 */
public final class PrinterConnection {
    public enum State { DISCONNECTED, PERMISSION, CONNECTING, CONNECTED, ERROR }

    public interface Listener {
        void onState(State state, String message);
    }

    private static final String ACTION_PERMISSION = "app.noko.print.USB_PERMISSION";

    private final Context app;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Listener listener;

    private State state = State.DISCONNECTED;
    private String message = "";
    private volatile UsbLink link;
    private int generation;
    private boolean permissionPending;
    private boolean registered;
    private boolean released;
    private int autoRetries;

    private final Runnable scanTask = this::scanNow;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            UsbDevice device = deviceFrom(intent);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device != null && UsbLink.isM1005(device)) onDetached();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (device != null && UsbLink.isM1005(device)) {
                    autoRetries = 0;
                    scanSoon();
                }
            } else if (ACTION_PERMISSION.equals(action)) {
                permissionPending = false;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    openAsync(device);
                } else {
                    setState(State.ERROR, "USB access was denied. Tap Connect and choose Allow.");
                }
            }
        }
    };

    public PrinterConnection(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        this.listener = listener;
    }

    private static UsbDevice deviceFrom(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        @SuppressWarnings("deprecation")
        UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        return d;
    }

    public State state() { return state; }

    public String message() { return message; }

    /** Call from Activity.onStart. */
    public void start() {
        if (released) return;
        if (!registered) {
            IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            registered = true;
        }
        scanSoon();
    }

    /** Call from Activity.onStop. The link itself is kept open. */
    public void stop() {
        main.removeCallbacks(scanTask);
        if (registered) {
            try {
                app.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // not registered
            }
            registered = false;
        }
    }

    public void release() {
        stop();
        released = true;
        generation++;
        closeLink();
        io.shutdownNow();
    }

    /** User pressed Connect / Retry. */
    public void connectNow() {
        autoRetries = 0;
        main.removeCallbacks(scanTask);
        scanNow();
    }

    /** Debounced re-check; attach events can arrive twice. */
    public void scanSoon() {
        main.removeCallbacks(scanTask);
        main.postDelayed(scanTask, 350);
    }

    /** Used by print jobs. */
    public synchronized UsbLink requireLink() throws IOException {
        UsbLink l = link;
        if (l == null || !l.isOpen() || state != State.CONNECTED) {
            throw new IOException("Printer is not connected. Plug it in and tap Connect.");
        }
        return l;
    }

    /** A transfer failed on this link. Safe to call from any thread. */
    public void reportFailure(UsbLink failed, String reason) {
        main.post(() -> {
            if (failed != link) return;
            closeLink();
            generation++;
            setState(State.ERROR, reason);
            if (autoRetries < 1) {
                autoRetries++;
                scanSoon();
            }
        });
    }

    private UsbDevice findPrinter() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (UsbLink.isM1005(d)) return d;
        }
        return null;
    }

    private void scanNow() {
        if (released) return;
        UsbDevice device = findPrinter();
        if (link != null) {
            if (device != null && link.isOpen()) {
                setState(State.CONNECTED, link.describe());
                return;
            }
            closeLink();
        }
        if (device == null) {
            permissionPending = false;
            generation++;
            setState(State.DISCONNECTED, "Plug the M1005 in with an OTG cable and switch it on.");
            return;
        }
        if (state == State.CONNECTING) return;
        if (!usb.hasPermission(device)) {
            if (permissionPending) return;
            permissionPending = true;
            setState(State.PERMISSION, "Allow USB access when Android asks.");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Intent intent = new Intent(ACTION_PERMISSION).setPackage(app.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(app, 1001, intent, flags);
            usb.requestPermission(device, pending);
            return;
        }
        openAsync(device);
    }

    private void openAsync(UsbDevice device) {
        if (released) return;
        if (link != null && link.isOpen()) {
            setState(State.CONNECTED, link.describe());
            return;
        }
        final int gen = ++generation;
        setState(State.CONNECTING, "Opening the printer…");
        io.execute(() -> {
            UsbLink opened = null;
            String error = null;
            try {
                UsbDeviceConnection connection = usb.openDevice(device);
                opened = UsbLink.open(device, connection);
            } catch (IOException e) {
                error = e.getMessage() == null ? "Could not open the printer." : e.getMessage();
            } catch (RuntimeException e) {
                error = "Could not open the printer. Replug it and try again.";
            }
            final UsbLink result = opened;
            final String err = error;
            main.post(() -> {
                if (gen != generation || released) {
                    if (result != null) result.close();
                    return;
                }
                if (result != null) {
                    link = result;
                    autoRetries = 0;
                    setState(State.CONNECTED, result.describe());
                } else {
                    setState(State.ERROR, err);
                }
            });
        });
    }

    private void onDetached() {
        generation++;
        permissionPending = false;
        autoRetries = 0;
        closeLink();
        setState(State.DISCONNECTED, "Printer unplugged. Plug it back in to continue.");
    }

    private void closeLink() {
        UsbLink l;
        synchronized (this) {
            l = link;
            link = null;
        }
        if (l != null) l.close();
    }

    private void setState(State next, String msg) {
        String text = msg == null ? "" : msg;
        if (next == state && text.equals(message)) return;
        // requireLink() reads state/link from the print thread.
        synchronized (this) {
            state = next;
            message = text;
        }
        listener.onState(next, text);
    }
}
