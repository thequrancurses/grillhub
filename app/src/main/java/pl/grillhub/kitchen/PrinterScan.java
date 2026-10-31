package pl.grillhub.kitchen;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Looks for printers: paired + nearby Bluetooth, Wi-Fi/LAN (port 9100) and USB. */
final class PrinterScan {

    static final class Found {
        final String type, addr, name, extra;
        Found(String type, String addr, String name, String extra) { this.type = type; this.addr = addr; this.name = name; this.extra = extra; }
    }

    interface Cb {
        void found(Found f);   // main thread
        void done();           // main thread, after every part finished
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private PrinterScan() {}

    private static int pending;
    private static BroadcastReceiver btReceiver;
    private static Context btCtx;

    /** Starts all scans. Safe to call again; the previous scan is cancelled. */
    static void start(Context ctx, boolean btAllowed, final Cb cb) {
        stop();
        final Context c = ctx.getApplicationContext();
        pending = 3;
        final Runnable part = () -> main.post(() -> { if (--pending == 0) cb.done(); });

        // USB (instant)
        for (Found f : usb(c)) cb.found(f);
        part.run();

        // Bluetooth: already paired + discovery
        if (btAllowed) bluetooth(c, cb, part); else part.run();

        // LAN
        lan(cb, part);
    }

    static void stop() {
        try {
            if (btReceiver != null && btCtx != null) btCtx.unregisterReceiver(btReceiver);
        } catch (Exception e) { /* ignore */ }
        btReceiver = null;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.isDiscovering()) ad.cancelDiscovery();
        } catch (Exception e) { /* ignore */ }
    }

    // ------------------------------------------------------------ USB

    private static List<Found> usb(Context c) {
        List<Found> out = new ArrayList<>();
        try {
            UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
            for (UsbDevice d : um.getDeviceList().values()) {
                boolean maybe = false;
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    UsbInterface it = d.getInterface(i);
                    if (it.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER || it.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) maybe = true;
                }
                if (!maybe) continue;
                String name = d.getProductName() != null ? d.getProductName() : "Drukarka USB";
                out.add(new Found("usb", Printer.usbKey(d), name, d.getManufacturerName() != null ? d.getManufacturerName() : "USB"));
            }
        } catch (Exception e) { /* ignore */ }
        return out;
    }

    // ------------------------------------------------------------ Bluetooth

    @SuppressWarnings("deprecation")
    private static void bluetooth(final Context c, final Cb cb, final Runnable part) {
        final BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) { part.run(); return; }
        final List<String> seen = new ArrayList<>();
        try {
            // paired devices first (printers on top)
            List<BluetoothDevice> bonded = new ArrayList<>(ad.getBondedDevices());
            Collections.sort(bonded, (a, b) -> (isPrinter(b) ? 1 : 0) - (isPrinter(a) ? 1 : 0));
            for (BluetoothDevice d : bonded) {
                seen.add(d.getAddress());
                cb.found(new Found("bt", d.getAddress(), nameOf(d), "sparowana" + (isPrinter(d) ? " · drukarka" : "")));
            }
            btCtx = c;
            btReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (d != null && !seen.contains(d.getAddress())) {
                            seen.add(d.getAddress());
                            cb.found(new Found("bt", d.getAddress(), nameOf(d), "w pobliżu · " + (isPrinter(d) ? "drukarka" : "urządzenie")));
                        }
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) {
                        try { c.unregisterReceiver(this); } catch (Exception e) { /* ignore */ }
                        btReceiver = null;
                        part.run();
                    }
                }
            };
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            c.registerReceiver(btReceiver, f);
            if (!ad.startDiscovery()) { stop(); part.run(); }
        } catch (SecurityException e) {
            part.run();
        } catch (Exception e) {
            part.run();
        }
    }

    private static boolean isPrinter(BluetoothDevice d) {
        try {
            BluetoothClass bc = d.getBluetoothClass();
            return bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING;
        } catch (Exception e) {
            return false;
        }
    }

    private static String nameOf(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (Exception e) { /* ignore */ }
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    // ------------------------------------------------------------ LAN (port 9100)

    private static void lan(final Cb cb, final Runnable part) {
        new Thread(() -> {
            String prefix = null;
            try {
                Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements() && prefix == null) {
                    NetworkInterface ni = en.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                            String h = a.getHostAddress();
                            prefix = h.substring(0, h.lastIndexOf('.') + 1);
                            break;
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
            if (prefix == null) { part.run(); return; }

            ExecutorService ex = Executors.newFixedThreadPool(48);
            final String pre = prefix;
            for (int i = 1; i <= 254; i++) {
                final String ip = pre + i;
                ex.execute(() -> {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(ip, 9100), 500);
                        main.post(() -> cb.found(new Found("net", ip, "Drukarka sieciowa", ip + " · port 9100")));
                    } catch (Exception e) { /* closed */ }
                    finally { try { s.close(); } catch (Exception e) { /* ignore */ } }
                });
            }
            ex.shutdown();
            try { ex.awaitTermination(40, TimeUnit.SECONDS); } catch (InterruptedException e) { /* ignore */ }
            part.run();
        }).start();
    }
}
