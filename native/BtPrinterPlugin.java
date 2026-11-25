package id.pawone.kasir;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/** Cetak ke printer thermal Bluetooth Classic (SPP). Printer harus dipasangkan dulu di pengaturan Bluetooth Android. */
@CapacitorPlugin(
    name = "BtPrinter",
    permissions = {
        @Permission(strings = { Manifest.permission.BLUETOOTH_CONNECT }, alias = "btConnect")
    }
)
public class BtPrinterPlugin extends Plugin {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 31 && getPermissionState("btConnect") != PermissionState.GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    @PluginMethod
    public void list(PluginCall call) {
        if (needsPermission()) {
            requestPermissionForAlias("btConnect", call, "listPermsCallback");
            return;
        }
        doList(call);
    }

    @PermissionCallback
    private void listPermsCallback(PluginCall call) {
        if (needsPermission()) {
            call.reject("Izin Bluetooth ditolak. Izinkan 'Perangkat sekitar' untuk aplikasi ini di pengaturan Android.");
            return;
        }
        doList(call);
    }

    @SuppressLint("MissingPermission")
    private void doList(PluginCall call) {
        BluetoothAdapter ad = adapter();
        if (ad == null) {
            call.reject("Perangkat ini tidak punya Bluetooth");
            return;
        }
        if (!ad.isEnabled()) {
            call.reject("Bluetooth mati. Nyalakan Bluetooth lalu coba lagi.");
            return;
        }
        JSArray arr = new JSArray();
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                JSObject o = new JSObject();
                String n = d.getName();
                o.put("name", n == null ? d.getAddress() : n);
                o.put("address", d.getAddress());
                arr.put(o);
            }
        }
        JSObject res = new JSObject();
        res.put("devices", arr);
        call.resolve(res);
    }

    @PluginMethod
    public void print(PluginCall call) {
        if (needsPermission()) {
            requestPermissionForAlias("btConnect", call, "printPermsCallback");
            return;
        }
        doPrint(call);
    }

    @PermissionCallback
    private void printPermsCallback(PluginCall call) {
        if (needsPermission()) {
            call.reject("Izin Bluetooth ditolak. Izinkan 'Perangkat sekitar' untuk aplikasi ini di pengaturan Android.");
            return;
        }
        doPrint(call);
    }

    @SuppressLint("MissingPermission")
    private void doPrint(final PluginCall call) {
        final String address = call.getString("address");
        final String b64 = call.getString("data");
        if (address == null || b64 == null) {
            call.reject("Data cetak tidak lengkap");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothSocket sock = null;
                try {
                    BluetoothAdapter ad = adapter();
                    if (ad == null || !ad.isEnabled()) {
                        call.reject("Bluetooth mati. Nyalakan Bluetooth lalu coba lagi.");
                        return;
                    }
                    BluetoothDevice dev = ad.getRemoteDevice(address);
                    try { ad.cancelDiscovery(); } catch (Exception ignored) { }
                    sock = dev.createRfcommSocketToServiceRecord(SPP);
                    try {
                        sock.connect();
                    } catch (Exception first) {
                        try { sock.close(); } catch (Exception ignored) { }
                        sock = (BluetoothSocket) dev.getClass()
                            .getMethod("createRfcommSocket", new Class[] { int.class })
                            .invoke(dev, 1);
                        sock.connect();
                    }
                    byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    OutputStream os = sock.getOutputStream();
                    int i = 0;
                    while (i < data.length) {
                        int n = Math.min(512, data.length - i);
                        os.write(data, i, n);
                        os.flush();
                        i += n;
                        Thread.sleep(25);
                    }
                    Thread.sleep(800);
                    call.resolve();
                } catch (Exception e) {
                    call.reject("Gagal terhubung ke printer. Pastikan printer menyala, sudah dipasangkan, dan berada dekat. (" + e.getMessage() + ")");
                } finally {
                    if (sock != null) {
                        try { sock.close(); } catch (Exception ignored) { }
                    }
                }
            }
        }).start();
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
